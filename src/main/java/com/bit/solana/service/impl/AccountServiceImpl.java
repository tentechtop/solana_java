package com.bit.solana.service.impl;

import com.bit.solana.result.Result;
import com.bit.solana.service.AccountService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class AccountServiceImpl implements AccountService {

    //生成助记词
    @Override
    public Result createMnemonic() {


        return Result.OK();
    }


}
