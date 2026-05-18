package com.bit.solana.tx.impl;

import com.bit.solana.blockchain.impl.LedgerCoordinator;
import com.bit.solana.result.Result;
import com.bit.solana.structure.dto.SubmitLedgerTransactionRequest;
import com.bit.solana.structure.dto.SubmitLedgerTransactionResponse;
import com.bit.solana.structure.vo.LedgerTransactionVO;
import com.bit.solana.tx.TxService;

public class TxServiceImpl implements TxService {
    private final LedgerCoordinator ledgerCoordinator;

    public TxServiceImpl(LedgerCoordinator ledgerCoordinator) {
        this.ledgerCoordinator = ledgerCoordinator;
    }

    @Override
    public Result<SubmitLedgerTransactionResponse> submitTx(SubmitLedgerTransactionRequest request) {
        return Result.OKData(ledgerCoordinator.submitTransaction(request));
    }

    @Override
    public Result<LedgerTransactionVO> getTransaction(String transactionId) {
        LedgerTransactionVO transaction = ledgerCoordinator.getTransaction(transactionId);
        return transaction == null ? Result.error("Transaction not found") : Result.OKData(transaction);
    }
}
