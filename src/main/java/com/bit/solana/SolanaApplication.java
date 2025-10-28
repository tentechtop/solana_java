package com.bit.solana;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@Slf4j
@SpringBootApplication(scanBasePackages = "com.bit.solana")
public class SolanaApplication {

    public static void main(String[] args) {
        SpringApplication.run(SolanaApplication.class, args);
    }

}
