package com.bit.solana.service.impl;

import com.bit.solana.result.Result;
import com.bit.solana.service.TxService;
import com.bit.solana.structure.tx.Transaction;
import com.bit.solana.txpool.TxPool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

@Slf4j
public class TxServiceImpl implements TxService {

    @Autowired
    private TxPool txPool;

    /**
     * 通过Http 提交一笔交易
     * @param tx
     * @return
     */
    @Override
    public Result<String> submitTx(Transaction tx) {
        return null;
    }
}
