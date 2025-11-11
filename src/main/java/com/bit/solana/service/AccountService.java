package com.bit.solana.service;

import com.bit.solana.result.Result;
import com.bit.solana.structure.account.json.AccountDTO;
import com.bit.solana.structure.dto.CreateAccountByMnemonicAndIndex;
import com.bit.solana.structure.tx.json.TransferTx;

public interface AccountService {
    Result createMnemonic();

    Result createAccount(CreateAccountByMnemonicAndIndex createAccountByMnemonicAndIndex);

    Result<Long> getBalance(String publicKey);

    Result<String> transfer(String fromPublicKey, String toPublicKey, long lamports, String privateKey);

    Result<AccountDTO> getAccountDetail(String publicKey);

    Result<String> submitTx(TransferTx transferTx);
}
