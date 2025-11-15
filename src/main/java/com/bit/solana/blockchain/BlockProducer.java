package com.bit.solana.blockchain;

import com.bit.solana.common.BlockHash;
import com.bit.solana.common.PoHHash;
import com.bit.solana.common.TransactionHash;
import com.bit.solana.structure.poh.POHRecord;
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
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Collectors;

/**
 * 项目验证阶段 稳定500ms出一个
 */

/**
 * 在Solana（及代码库设计的逻辑）中，出块并非简单的“定时触发”，而是结合**POH（历史证明）时序机制**和**插槽（Slot）调度**实现的精准出块节奏，核心特点如下：
 *
 *
 * ### 1. 基于POH的“逻辑时钟”驱动，而非物理定时
 * - **POH的核心作用**：POH通过加密哈希链生成全局统一的时序，每个哈希计算对应固定的时间间隔（约400ms/哈希，可配置），形成区块链的“逻辑时钟”。
 * - **插槽（Slot）与出块间隔**：每个Slot对应一个POH哈希周期（代码中`BlockHeader.slot`字段），设计目标为500ms/块（技术.txt约束），与Slot周期强绑定。
 * - **区别于传统定时任务**：
 *   物理定时（如`ScheduledExecutorService`）可能受节点本地时钟偏差影响，而POH通过全网共识的哈希链确保时序统一，出块触发由POH的哈希推进事件驱动，而非单纯依赖本地定时器。
 *
 *
 * ### 2. 区块生产者的“Slot所有权”机制
 * - **Slot分配**：每个Slot会预先指定一个区块生产者（由验证者质押权重和POH随机数决定），该生产者在Slot周期内拥有出块权。
 * - **出块触发时机**：当POH推进到当前Slot的哈希位置时，区块生产者开始打包交易并生成区块，确保在Slot周期结束前完成（否则视为出块失败，可能触发分叉）。
 * - **代码映射**：
 *   在`BlockProducer`组件中，出块逻辑虽通过定时任务模拟（便于初期验证），但实际会与`POHService`同步，当`pohService.getCurrentPOH().getHeight()`达到Slot对应的哈希高度时，才触发`produceBlock()`。
 *
 *
 * ### 3. 灵活性与约束的平衡
 * - **固定间隔的本质**：技术.txt明确“出块间隔固定500ms”，这是通过POH的Slot周期保障的，最终表现为“每秒2个区块”的稳定节奏。
 * - **允许空块或交易不足**：若交易池不足5000笔，区块生产者仍需在Slot周期内生成空块（或交易不足的区块），维持链的连续性（`BlockStatus`枚举中`UNVERIFIED`到`CONFIRMED`的状态流转支持此逻辑）。
 * - **监控验证**：`ServerMonitor`接口设计中包含“出块速度”监控项，实际部署时会跟踪每个区块的`blockTime`（`BlockHeader`字段）与上一区块的时间差，确保偏差在允许范围内（如±50ms）。
 *
 *
 * ### 总结
 * Solana的出块机制是**“基于POH逻辑时钟的Slot调度”**，而非传统意义上的本地定时任务。其核心是通过全网共识的时序基准（POH）确保每个区块在预设的时间间隔（500ms）内生成，既保证了出块节奏的稳定性，又避免了本地时钟偏差导致的全网不一致。代码中用定时任务模拟是为了简化初期验证，最终会与POH服务强绑定以实现分布式时序一致性。
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
/*        // 初始化定时任务，固定500ms执行一次出块逻辑
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
        );*/
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
        header.setPreviousBlockHash(latestBlock != null ? latestBlock.getBlockHash() : BlockHash.EMPTY);
        // POH时序信息：从POH服务获取当前哈希和高度
        POHRecord pohRecord = pohService.getCurrentPOH();
        header.setPoHHash(PoHHash.fromBytes(pohRecord.getCurrentHash()));
        header.setPoHHeight(pohRecord.getSequenceNumber());
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
