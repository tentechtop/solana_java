package com.bit.solana.blockchain;

import com.bit.solana.common.BlockHash;
import com.bit.solana.common.TransactionHash;
import com.bit.solana.poh.POHRecord;
import com.bit.solana.poh.POHService;
import com.bit.solana.structure.block.Block;
import com.bit.solana.structure.block.BlockBody;
import com.bit.solana.structure.block.BlockHeader;
import com.bit.solana.structure.bloom.AccountConflictBloom;
import com.bit.solana.structure.tx.Transaction;
import com.bit.solana.txpool.TxPool;
import com.bit.solana.util.TxUtils;
import com.bit.solana.voting.VotingService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 项目验证阶段 稳定500ms出一个
 */
@Slf4j
@Component
public class BlockProducer {
    // 固定出块间隔：500ms
    private static final long BLOCK_INTERVAL_MS = 500;
    @Autowired
    private BlockChain blockChain; // 依赖区块链核心接口
    @Autowired
    private TxPool txPool; // 依赖交易池获取待处理交易
    @Autowired
    private POHService pohService; // 依赖POH服务生成时序信息
    @Autowired
    private VotingService votingService; // 依赖投票服务触发共识

    // 定时任务调度器（单线程确保出块顺序）
    private ScheduledExecutorService blockScheduler;
    @PostConstruct
    public void init() {
        // 初始化定时任务，固定500ms执行一次出块逻辑
        blockScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "block-producer");
            thread.setDaemon(true);
            return thread;
        });

        // 延迟0ms启动，每500ms执行一次
        blockScheduler.scheduleAtFixedRate(
                this::produceBlock,
                0,
                BLOCK_INTERVAL_MS,
                TimeUnit.MILLISECONDS
        );
    }

    /**
     * 生成区块的核心逻辑
     */
    private void produceBlock() {
        try {
            // 1. 从交易池提取交易（按TPS目标计算：1万TPS / 2区块/秒 = 5000笔/区块）
            int maxTxPerBlock = 5000; // 技术.txt约束：每个区块至少5000笔
            List<Transaction> pendingTxs = txPool.getPendingTransactions(maxTxPerBlock);

            // 2. 生成区块头（包含POH时序、前序区块哈希等）
            BlockHeader header = createBlockHeader();

            // 3. 生成区块体（包含交易列表、哈希等）
            BlockBody body = createBlockBody(pendingTxs);

            // 4. 组装完整区块
            Block block = new Block();
            block.setHeader(header);
            block.setBody(body);
            block.setTotalSize(header.getHeaderSize() + body.getTotalSize());
            // 其他必要字段赋值（如slot、parentBlockHash等）

            // 5. 提交区块到区块链核心处理（验证+共识）
            blockChain.processBlock(block);

            // 6. 移除已打包交易（避免重复处理）
            List<String> txIds = pendingTxs.stream()
                    .map(tx -> tx.getTransactionHash().toString())
                    .collect(Collectors.toList());
            txPool.removeProcessedTransactions(txIds);

        } catch (Exception e) {
            log.error("区块生成失败", e);
        }
    }

    /**
     * 创建区块头（结合POH时序）
     */
    private BlockHeader createBlockHeader() {
        BlockHeader header = new BlockHeader();
        // 前序区块哈希：从区块链获取最新区块的哈希
        Block latestBlock = blockChain.getLatestBlock();
        header.setPreviousBlockhash(latestBlock != null ? latestBlock.getBlockHash() : BlockHash.EMPTY);
        // POH时序信息：从POH服务获取当前哈希和高度
        POHRecord pohRecord = pohService.getCurrentPOH();
        header.setPoHHash(pohRecord.getHash());
        header.setPoHHeight(pohRecord.getHeight());
        // 其他字段：slot、blockTime等
        header.setSlot(calculateSlot()); // 基于POH高度计算slot（约400ms/slot，可调整为500ms对应）
        header.setBlockTime(System.currentTimeMillis());
        return header;
    }

    /**
     * 创建区块体（包含交易及冲突检测信息）
     */
    private BlockBody createBlockBody(List<Transaction> transactions) {
        BlockBody body = new BlockBody();
        body.setTransactions(transactions);
        // 计算交易哈希列表
        List<TransactionHash> txHashes = transactions.stream()
                .map(Transaction::getTransactionHash)
                .collect(Collectors.toList());
        body.setTransactionHashes(txHashes);
        body.setTransactionsCount(transactions.size());
        // 计算区块体大小（序列化后）
        body.setTotalSize(calculateBodySize(transactions, txHashes));
        // 账户冲突布隆过滤器（用于并行验证优化）
        body.setAccountConflictBloom(buildAccountConflictBloom(transactions));
        return body;
    }


    // 其他辅助方法：计算slot、区块体大小、布隆过滤器等
    private long calculateSlot() {
        // 基于POH高度或时间计算，确保与500ms出块间隔匹配
        return System.currentTimeMillis() / BLOCK_INTERVAL_MS;
    }

    private long calculateBodySize(List<Transaction> transactions, List<TransactionHash> txHashes) {
        // 实际实现需序列化交易和哈希并计算总字节数
        return transactions.stream().mapToLong(TxUtils::getSerializedSize).sum()
                + txHashes.stream().mapToLong(hash -> hash.getBytes().length).sum();
    }

    private AccountConflictBloom buildAccountConflictBloom(List<Transaction> transactions) {
        AccountConflictBloom bloom = AccountConflictBloom.createEmpty();
        transactions.forEach(tx -> tx.getAccounts().forEach(account -> bloom.add(account.getPublicKey())));
        return bloom;
    }

    @PreDestroy
    public void shutdown() {
        blockScheduler.shutdown();
    }

}
