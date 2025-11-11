package com.bit.solana.service.impl;

import com.bit.solana.result.Result;
import com.bit.solana.service.AccountService;
import com.bit.solana.structure.account.json.AccountDTO;
import com.bit.solana.structure.dto.CreateAccountByMnemonicAndIndex;
import com.bit.solana.structure.key.KeyInfo;
import com.bit.solana.structure.tx.Transaction;
import com.bit.solana.structure.tx.json.TransferTx;
import com.bit.solana.txpool.TxPool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.bit.solana.util.Ed25519HDWallet.generateMnemonic;
import static com.bit.solana.util.Ed25519HDWallet.getSolanaKeyPair;

/**
 * 租户模式  当账户金额大于等于 10sol  永久保留账户 当小于10sol  每月扣除1SOL  直到余额为零 等待清除
 */

@Slf4j
@Component
public class AccountServiceImpl implements AccountService {

    @Autowired
    private TxPool txPool;

    //生成助记词
    @Override
    public Result createMnemonic() {
        List<String> mnemonic = generateMnemonic();
        String mnemonicStr = String.join(" ", mnemonic);
        return Result.OKData(mnemonicStr);
    }

    @Override
    public Result createAccount(CreateAccountByMnemonicAndIndex req) {
        String mnemonic = req.getMnemonic();
        String[] s = mnemonic.split(" ");
        KeyInfo baseKey = getSolanaKeyPair(List.of(s), req.getAccountIndex(), req.getAddressIndex());
        return Result.OKData(baseKey.getAddress());
    }

    @Override
    public Result<Long> getBalance(String publicKey) {
        return Result.OKData((long)0);
    }

    @Override
    public Result<String> transfer(String fromPublicKey, String toPublicKey, long lamports, String privateKey) {
        return null;
    }

    @Override
    public Result<AccountDTO> getAccountDetail(String publicKey) {
        AccountDTO accountDTO = new AccountDTO();
        return Result.OK(accountDTO);
    }

    @Override
    public Result<String> submitTx(TransferTx transferTx) {
        Transaction transaction  = convertTx(transferTx);
        return txPool.addTransaction(transaction);
    }

    private Transaction convertTx(TransferTx transferTx) {
        Transaction transaction = new Transaction();
        log.info("转化后的交易{}",transaction);
        return transaction;
    }


}
