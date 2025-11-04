package com.bit.solana.txpool;

import com.bit.solana.result.Result;
import com.bit.solana.structure.tx.Transaction;
import com.bit.solana.structure.tx.TransactionGroup;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class TxPool implements TxPoolInterface{

    //交易池
    private final Map<byte[], Transaction> txMap  = new ConcurrentHashMap<>();
    // 交易分组（groupId -> 交易组，groupId通常为sender账户）
    private final ConcurrentMap<byte[], TransactionGroup> txGroups = new ConcurrentHashMap<>();
    // 并行处理线程池（核心线程数=CPU核心数）
    private final ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());


    @Override
    public Result getTxPool() {
        return null;
    }

    @Override
    public Result getTxPoolStatus() {
        return null;
    }

    @Override
    public Result verifyTransaction(Transaction tx) {
        return null;
    }


    @Override
    public Result addTransaction(Transaction tx) {
        // 1. 生成POH时间戳

        // 2. 加入交易池
        txMap.put(tx.getTxId(), tx);
        // 3. 按发送者（sender）分组：同一发送者的交易划入同一组
        byte[] groupId = tx.getSender();  // 分组依据：发送者账户（关键冲突账户）
        // 若组不存在则创建，存在则直接添加交易
        txGroups.computeIfAbsent(groupId, k -> new TransactionGroup(groupId))
                .getTransactions()
                .add(tx);

        return Result.OK();
    }

    /**
     * 不同组 无冲突 可并行
     */
    @Override
    public void processTransactions() {

    }


    /**
     * 处理单个交易组（组内交易串行处理）
     * @param group
     */
    private void processGroup(TransactionGroup group) {
        // 按POH时间戳排序组内交易（确保顺序性）
        // 串行处理组内交易
        // 交易验证（签名校验、余额检查等）
    }

}
