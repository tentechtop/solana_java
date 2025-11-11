package com.bit.solana.api;

import com.bit.solana.result.Result;
import com.bit.solana.service.AccountService;
import com.bit.solana.structure.account.json.AccountDTO;
import com.bit.solana.structure.dto.CreateAccountByMnemonicAndIndex;
import com.bit.solana.structure.tx.json.TransferTx;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/account")
public class AccountApi {

    @Autowired
    private AccountService accountService;

    //生成助手记词
    @GetMapping("/createMnemonic")
    public Result createMnemonic() {
        return accountService.createMnemonic();
    }

    //通过助记住词 和 账户分层 地址分层 创建账户和地址
    @PostMapping("/createAccount")
    public Result createAccount(@RequestBody CreateAccountByMnemonicAndIndex createAccountByMnemonicAndIndex) {
        return accountService.createAccount(createAccountByMnemonicAndIndex);
    }

    // 查询账户余额
    @GetMapping("/balance")
    public Result<Long> getBalance(@RequestParam String publicKey) {
        return accountService.getBalance(publicKey);
    }

    // 发起转账交易
    @PostMapping("/transfer")
    public Result<String> transfer(
            @RequestParam String fromPublicKey,
            @RequestParam String toPublicKey,
            @RequestParam long lamports,
            @RequestParam String privateKey) {
        return accountService.transfer(fromPublicKey, toPublicKey, lamports, privateKey);
    }

    // 获取账户详情
    @GetMapping("/detail")
    public Result<AccountDTO> getAccountDetail(@RequestParam String publicKey) {
        return accountService.getAccountDetail(publicKey);
    }


    // 发起一笔交易 如智能合约交易
    @PostMapping("/submit")
    public Result<String> submitTx(@RequestBody TransferTx transferTx) {
        return accountService.submitTx(transferTx);
    }

}
