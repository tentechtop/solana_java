package com.bit.solana.service.impl;

import com.bit.solana.result.Result;
import com.bit.solana.service.TxService;
import com.bit.solana.structure.tx.Transaction;
import com.bit.solana.structure.tx.TransactionStatus;
import com.bit.solana.txpool.TxPool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Slf4j
@Component
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
        // 1. 前置校验（空值与格式快速检查）
        if (tx == null || tx.getSignatures() == null || tx.getSignatures().isEmpty()) {
            return Result.error("无效交易：缺少签名");
        }

        return null;
    }
}
