package com.bit.solana.txpool;

import com.bit.solana.structure.tx.Transaction;

import java.util.List;

public interface SubmitPool {

    List<Transaction> selectAndRemoveTopTransactions();
    boolean addTransaction(Transaction transaction);
    int cleanExpiredTransactions(long currentTime);
    long getTotalTransactionSize();
    int getTotalTransactionCount();
    boolean removeTransactionByTxId(String txId);
    Transaction findTransactionByTxId(String txId);
}
