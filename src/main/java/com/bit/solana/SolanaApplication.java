package com.bit.solana;

import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.security.MessageDigest;
import java.security.Security;

@Slf4j
@SpringBootApplication(scanBasePackages = "com.bit.solana")
public class SolanaApplication {
    public static void main(String[] args) {
        SpringApplication.run(SolanaApplication.class, args);
    }
    //二进制统一大端
    //零值表示成功，非零值表示失败
}
