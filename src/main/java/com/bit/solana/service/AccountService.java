package com.bit.solana.service;

import com.bit.solana.result.Result;
import com.bit.solana.structure.dto.CreateAccountByMnemonicAndIndex;

public interface AccountService {
    Result createMnemonic();

    Result createAccount(CreateAccountByMnemonicAndIndex createAccountByMnemonicAndIndex);
}
