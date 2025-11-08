package com.bit.solana.txpool;

import com.bit.solana.result.Result;
import com.bit.solana.structure.tx.Transaction;
import com.bit.solana.structure.tx.TransactionStatus;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface TxPool {

    /**
     * 提交交易到交易池（异步）
     * @param transaction 待提交交易
     * @return 处理结果Future
     */
    CompletableFuture<Boolean> submitTransaction(Transaction transaction);

    /**
     * 批量提交交易（异步）
     * @param transactions 交易列表
     * @return 处理结果Future列表
     */
    List<CompletableFuture<Boolean>> batchSubmitTransactions(List<Transaction> transactions);

    /**
     * 获取待处理交易（按优先级）
     * @param maxCount 最大获取数量
     * @return 交易列表
     */
    List<Transaction> getPendingTransactions(int maxCount);

    /**
     * 移除已处理的交易
     * @param transactionIds 已处理交易ID列表
     */
    void removeProcessedTransactions(List<String> transactionIds);

    /**
     * 获取当前交易池大小
     * @return 交易数量
     */
    int getPoolSize();

    /**
     * 验证交易有效性
     * @param transaction 待验证交易
     * @return 验证结果
     */
    boolean validateTransaction(Transaction transaction);


    Result getTxPool();

    // 获取交易状态
    TransactionStatus getStatus(byte[] txId);

    Result<String> getTxPoolStatus();

    //交易池的验证功能
    //交易池 (也称内存池 / Mempool) 是区块链节点的重要组件，主要负责：
    //初步验证：检查交易的基本格式、签名有效性和发送方余额是否充足
    //防双花检查：验证交易是否已被处理或存在 "双花" 风险
    //格式验证：确保交易符合网络协议规则和静态格式要求
    //暂存管理：将通过初步验证的交易暂存，等待打包进区块
    //交易池验证的特点是局部性和临时性，它只验证交易的基本要素，不涉及区块链全局状态的最终确认。
    /**
     * 交易验证
     * @param tx
     * @return true/验证成功 无消息 false/验证失败 有失败消息
     */
    Result<String> verifyTransaction(Transaction tx);


    /**
     * 添加一笔交易到交易池中
     * @param tx
     * @return true/添加成功 无消息 false/添加失败 有失败消息
     */
    Result<String> addTransaction(Transaction tx);  // 添加交易

    void processTransactions();       // 并行处理交易
}
