package com.bit.solana.api.mock;

import com.bit.solana.result.Result;
import com.bit.solana.structure.key.KeyInfo;
import com.bit.solana.util.VectorEd25519Signer;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.Base58;
import org.bouncycastle.util.encoders.Hex;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Arrays;
import java.util.List;

import static com.bit.solana.util.ByteUtils.bytesToHex;
import static com.bit.solana.util.Ed25519HDWallet.generateMnemonic;
import static com.bit.solana.util.Ed25519HDWallet.getSolanaKeyPair;
import static com.bit.solana.util.Ed25519Signer.*;

@Slf4j
@RestController
@RequestMapping("/mock")
public class MockApi {

    @GetMapping("/mock1")
    public Result createMnemonic() throws NoSuchAlgorithmException {
        // 1. 生成密钥对
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("Ed25519");
        KeyPair keyPair = keyGen.generateKeyPair();
        PrivateKey privateKey = keyPair.getPrivate();
        PublicKey publicKey = keyPair.getPublic();




        return Result.OK();
    }


    public static void main(String[] args) {
        // 生成助记词和密钥对
        List<String> mnemonic = generateMnemonic();
        System.out.println("助记词: " + String.join(" ", mnemonic));
        KeyInfo keyInfo = getSolanaKeyPair(mnemonic, 0, 0);

        // 提取核心密钥字节
        byte[] corePriv = keyInfo.getPrivateKey();
        byte[] corePub = derivePublicKeyFromPrivateKey(corePriv);

        log.info("公钥: {}", Hex.toHexString(corePub));
        String address = Base58.encode(corePub);
        log.info("地址: {}", address);
        log.info("核心公钥长度: {}字节", corePub.length);
        log.info("核心私钥长度: {}字节", corePriv.length);

        // 恢复密钥对
        PublicKey recoveredPub = recoverPublicKeyFromCore(corePub);
        PrivateKey recoveredPriv = recoverPrivateKeyFromCore(corePriv);

        // 测试数据
        byte[] data = "测试数据".getBytes(StandardCharsets.UTF_8);
        int loopCount = 100; // 循环次数

        // 累计耗时（纳秒）
        long totalSignTime = 0;
        long totalVerifyTime = 0;

        // 循环执行签名和验签
        for (int i = 0; i < loopCount; i++) {
            // 签名
            long signStart = System.nanoTime();
            byte[] signature = applySignature(recoveredPriv, data);
            long signEnd = System.nanoTime();
            totalSignTime += (signEnd - signStart);

            // 验签
            long verifyStart = System.nanoTime();
            boolean verifyResult = verifySignature(recoveredPub, data, signature);
            long verifyEnd = System.nanoTime();
            totalVerifyTime += (verifyEnd - verifyStart);

            // 验证每次验签结果是否正确
            if (!verifyResult) {
                log.warn("第{}次验签失败", i + 1);
            }
        }

        // 计算平均时间（转换为毫秒，保留4位小数）
        double avgSignTimeNanos = (double) totalSignTime / loopCount;
        double avgSignTimeMillis = avgSignTimeNanos / 1_000_000.0;

        double avgVerifyTimeNanos = (double) totalVerifyTime / loopCount;
        double avgVerifyTimeMillis = avgVerifyTimeNanos / 1_000_000.0;

        // 输出结果
        log.info("\n===== 100次循环平均耗时 =====");
        log.info("平均签名时间: {} 纳秒 ({} 毫秒)", avgSignTimeNanos, avgSignTimeMillis);
        log.info("平均验签时间: {} 纳秒 ({} 毫秒)", avgVerifyTimeNanos, avgVerifyTimeMillis);
    }
}
