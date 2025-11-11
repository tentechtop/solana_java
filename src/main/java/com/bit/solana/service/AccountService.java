package com.bit.solana.service;

import com.bit.solana.result.Result;
import com.bit.solana.structure.dto.AccountDTO;
import com.bit.solana.structure.dto.CreateAccountByMnemonicAndIndex;

public interface AccountService {
    Result createMnemonic();

    Result createAccount(CreateAccountByMnemonicAndIndex createAccountByMnemonicAndIndex);

    Result<Long> getBalance(String publicKey);

    Result<String> transfer(String fromPublicKey, String toPublicKey, long lamports, String privateKey);

    Result<AccountDTO> getAccountDetail(String publicKey);
}
