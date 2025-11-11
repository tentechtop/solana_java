package com.bit.solana.api;

import com.bit.solana.monitor.impl.dto.CpuMetrics;
import com.bit.solana.result.Result;
import com.bit.solana.service.AccountService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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



    //创建账户


}
