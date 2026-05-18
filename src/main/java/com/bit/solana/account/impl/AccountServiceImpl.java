package com.bit.solana.account.impl;

import com.bit.solana.account.AccountService;
import com.bit.solana.blockchain.impl.LedgerCoordinator;
import com.bit.solana.result.Result;
import com.bit.solana.structure.dto.CreateLedgerAccountRequest;
import com.bit.solana.structure.dto.CreateLedgerAccountResponse;
import com.bit.solana.structure.dto.SubmitLedgerTransactionRequest;
import com.bit.solana.structure.dto.SubmitLedgerTransactionResponse;
import com.bit.solana.structure.vo.LedgerAccountVO;

public class AccountServiceImpl implements AccountService {
    private final LedgerCoordinator ledgerCoordinator;

    public AccountServiceImpl(LedgerCoordinator ledgerCoordinator) {
        this.ledgerCoordinator = ledgerCoordinator;
    }

    @Override
    public Result<CreateLedgerAccountResponse> createAccount(CreateLedgerAccountRequest request) {
        return Result.OKData(ledgerCoordinator.createAccount(request));
    }

    @Override
    public Result<LedgerAccountVO> getAccount(String accountId) {
        LedgerAccountVO account = ledgerCoordinator.getAccount(accountId);
        return account == null ? Result.error("Account not found") : Result.OKData(account);
    }

    @Override
    public Result<Long> getBalance(String accountId) {
        LedgerAccountVO account = ledgerCoordinator.getAccount(accountId);
        return account == null ? Result.error("Account not found") : Result.OKData(account.getLamports());
    }

    @Override
    public Result<SubmitLedgerTransactionResponse> transfer(SubmitLedgerTransactionRequest request) {
        return Result.OKData(ledgerCoordinator.submitTransaction(request));
    }
}
