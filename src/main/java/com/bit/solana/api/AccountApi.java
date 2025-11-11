package com.bit.solana.api;

import com.bit.solana.monitor.impl.dto.CpuMetrics;
import com.bit.solana.result.Result;
import com.bit.solana.service.AccountService;
import com.bit.solana.structure.dto.CreateAccountByMnemonicAndIndex;
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

    // 查询账户历史交易分页 指定倒序 正序  每页大小 当前页

    // 转账

    // 发起一笔交易 如智能合约交易


}
