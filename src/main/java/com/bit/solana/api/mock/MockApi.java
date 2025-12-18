package com.bit.solana.api.mock;

import com.bit.solana.p2p.impl.PeerServiceImpl;
import com.bit.solana.quic.QuicConnection;
import com.bit.solana.result.Result;
import com.bit.solana.structure.key.KeyInfo;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.Base58;
import org.bouncycastle.util.encoders.Hex;
import org.checkerframework.checker.units.qual.A;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static com.bit.solana.quic.QuicConnectionManager.getFirstConnection;
import static com.bit.solana.util.Ed25519HDWallet.generateMnemonic;
import static com.bit.solana.util.Ed25519HDWallet.getSolanaKeyPair;
import static com.bit.solana.util.SolanaEd25519Signer.*;

@Slf4j
@RestController
@RequestMapping("/mock")
public class MockApi {

    @Autowired
    private PeerServiceImpl peerService;

    @GetMapping("/sendMock")
    public Result sendMock() throws NoSuchAlgorithmException {
        QuicConnection firstConnection = getFirstConnection();
        if (firstConnection != null){
            // 1. 生成2048字节的测试数据（核心修改点）
            int targetLength = 1024 * 1024 * 2;
            byte[] mockData = new byte[targetLength]; // 初始化2048字节数组
            // 可选：填充固定字符（比如用 'a' 填充，避免全零数据）
            // 每个字节填充为字符'a'的ASCII码
            Arrays.fill(mockData, (byte) 'a');

            // 2. 发送2048字节数据
            boolean b = firstConnection.sendData(mockData);
        }else {
            Result.error("连接已经断开");
        }
        return Result.OK();
    }




    @GetMapping("/mock1")
    public Result createMnemonic() throws NoSuchAlgorithmException {
        // 1. 生成密钥对
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("Ed25519");
        KeyPair keyPair = keyGen.generateKeyPair();
        PrivateKey privateKey = keyPair.getPrivate();
        PublicKey publicKey = keyPair.getPublic();




        return Result.OK();
    }

    /**
     * 18:33:53.621 [main] INFO org.bitcoinj.crypto.MnemonicCode -- PBKDF2 took 88.71 ms
     * 18:33:53.818 [main] INFO com.bit.solana.api.mock.MockApi -- 公钥: 781a87b255f8fc485eb2d2576fce16ef1685933daaed57751006dade46f806ce
     * 18:33:53.819 [main] INFO com.bit.solana.api.mock.MockApi -- 地址: 95qPT48SoKcc83eE6QXSDGjwkmCN7ur8LtSofiYGbQ8V
     * 18:33:53.819 [main] INFO com.bit.solana.api.mock.MockApi -- 核心公钥长度: 32字节
     * 18:33:53.819 [main] INFO com.bit.solana.api.mock.MockApi -- 核心私钥长度: 32字节
     * 18:33:54.970 [main] INFO com.bit.solana.api.mock.MockApi --
     * ===== 100次循环平均耗时 =====
     * 18:33:54.970 [main] INFO com.bit.solana.api.mock.MockApi -- 平均签名时间: 49776.65 纳秒 (0.04977665 毫秒)
     * 18:33:54.970 [main] INFO com.bit.solana.api.mock.MockApi -- 平均验签时间: 64566.75 纳秒 (0.06456675 毫秒)
     */

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

        Random random = new Random();

        // 测试数据
        byte[] data = ("测试数据" + random.nextInt(1000)).getBytes(StandardCharsets.UTF_8);
        int loopCount = 10000; // 循环次数

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
        log.info("\n===== {}次循环平均耗时 =====", loopCount);
        log.info("平均签名时间: {} 纳秒 ({} 毫秒)", avgSignTimeNanos, avgSignTimeMillis);
        log.info("平均验签时间: {} 纳秒 ({} 毫秒)", avgVerifyTimeNanos, avgVerifyTimeMillis);


        // ---------------------- 新增：单次签名和验证时间测试 ----------------------
        // 单次签名
        long singleSignStart = System.nanoTime();
        byte[] singleSignature = applySignature(recoveredPriv, data);
        long singleSignEnd = System.nanoTime();
        long singleSignNanos = singleSignEnd - singleSignStart;
        double singleSignMillis = singleSignNanos / 1_000_000.0;
        // 单次验签
        long singleVerifyStart = System.nanoTime();
        boolean singleVerifyResult = verifySignature(recoveredPub, data, singleSignature);
        long singleVerifyEnd = System.nanoTime();
        long singleVerifyNanos = singleVerifyEnd - singleVerifyStart;
        double singleVerifyMillis = singleVerifyNanos / 1_000_000.0;

        log.info("\n===== 单次签名和验证时间 =====");
        log.info("单次签名时间: {} 纳秒 ({} 毫秒)", singleSignNanos, singleSignMillis);
        log.info("单次验签结果: {}", singleVerifyResult ? "成功" : "失败");
        log.info("单次验签时间: {} 纳秒 ({} 毫秒)", singleVerifyNanos, singleVerifyMillis);
        // -------------------------------------------------------------------------


    }
}
