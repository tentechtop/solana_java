package com.bit.solana.txpool;

import com.bit.solana.structure.tx.Transaction;

import java.util.List;

/**
 * 交易处理器接口
 * 负责处理从交易池取出的交易
 */
public interface TransactionProcessor {
    /**
     * 处理交易
     * @param transactions 待处理交易
     * @return 处理成功的交易数量
     */
    int processTransactions(List<Transaction> transactions);

    /**
     * 启动处理器
     */
    void start();

    /**
     * 停止处理器
     */
    void stop();
}