package com.bit.solana.account;

import com.bit.solana.result.Result;
import com.bit.solana.structure.account.Account;
import com.bit.solana.structure.account.json.AccountDTO;
import com.bit.solana.structure.dto.CreateAccountByMnemonicAndIndex;
import com.bit.solana.structure.tx.json.TransferTx;

public interface AccountService {

    /**
     * 该账户下的交易 分页查询
     */

    /**
     * 账户是否豁免租金
     */

    /**
     * 账户到期时间  如果金额充足就不到期
     */

    /**
     * 账户是否存在
     */






    Account getAccountByHash(byte[] hash);

    Result createMnemonic();

    Result createAccount(CreateAccountByMnemonicAndIndex createAccountByMnemonicAndIndex);

    Result<Long> getBalance(String publicKey);

    Result<String> transfer(String fromPublicKey, String toPublicKey, long lamports, String privateKey);

    Result<AccountDTO> getAccountDetail(String publicKey);

    Result<String> submitTx(TransferTx transferTx);
}
