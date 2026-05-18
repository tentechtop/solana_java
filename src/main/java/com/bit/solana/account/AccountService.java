package com.bit.solana.account;

import com.bit.solana.result.Result;
import com.bit.solana.structure.dto.CreateLedgerAccountRequest;
import com.bit.solana.structure.dto.CreateLedgerAccountResponse;
import com.bit.solana.structure.dto.SubmitLedgerTransactionRequest;
import com.bit.solana.structure.dto.SubmitLedgerTransactionResponse;
import com.bit.solana.structure.vo.LedgerAccountVO;

public interface AccountService {
    Result<CreateLedgerAccountResponse> createAccount(CreateLedgerAccountRequest request);

    Result<LedgerAccountVO> getAccount(String accountId);

    Result<Long> getBalance(String accountId);

    Result<SubmitLedgerTransactionResponse> transfer(SubmitLedgerTransactionRequest request);
}
