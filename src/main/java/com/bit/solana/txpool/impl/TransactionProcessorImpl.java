package com.bit.solana.txpool.impl;

import com.bit.solana.structure.tx.Transaction;
import com.bit.solana.txpool.TransactionProcessor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class TransactionProcessorImpl implements TransactionProcessor {
    @Override
    public int processTransactions(List<Transaction> transactions) {
        return 0;
    }

    @Override
    public void start() {

    }

    @Override
    public void stop() {

    }
}
