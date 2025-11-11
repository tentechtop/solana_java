package com.bit.solana.service.impl;

import com.bit.solana.result.Result;
import com.bit.solana.service.AccountService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.bit.solana.util.Ed25519HDWallet.generateMnemonic;

@Slf4j
@Component
public class AccountServiceImpl implements AccountService {

    //生成助记词
    @Override
    public Result createMnemonic() {
        List<String> mnemonic = generateMnemonic();
        //输出成字符串
        // 将列表元素用空格拼接成字符串
        String mnemonicStr = String.join(" ", mnemonic);
        // 输出字符串（可选，用于调试）
        System.out.println("生成的助记词：" + mnemonicStr);
        // 将字符串放入返回结果
        return Result.OKData(mnemonicStr);
    }


}
