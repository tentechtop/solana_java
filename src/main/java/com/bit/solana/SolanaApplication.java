package com.bit.solana;

import com.bit.solana.util.Secp256k1Signer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.List;

import static com.bit.solana.util.ECCWithAESGCM.generateCurve25519KeyPair;
import static com.bit.solana.util.Ed25519HDWallet.generateMnemonic;
import static com.bit.solana.util.Ed25519HDWallet.getSolanaKeyPair;

@Slf4j
@SpringBootApplication(scanBasePackages = "com.bit.solana")
public class SolanaApplication {
    public static void main(String[] args) {
        System.setProperty("io.netty.leakDetection.level", "PARANOID");
        SpringApplication.run(SolanaApplication.class, args);

        long start = System.currentTimeMillis();
        generateCurve25519KeyPair();
        Secp256k1Signer.generateKeyPair();
        List<String> mnemonic = generateMnemonic();
        getSolanaKeyPair(mnemonic, 0, 0);
        log.info("预热耗时{}ms",System.currentTimeMillis()-start);
    }
    //二进制统一大端
    //零值表示成功，非零值表示失败
}
