package com.bit.solana.service;

import com.bit.solana.result.Result;
import com.bit.solana.structure.tx.Transaction;

public interface TxService {

    Result<String> submitTx(Transaction tx);
}
