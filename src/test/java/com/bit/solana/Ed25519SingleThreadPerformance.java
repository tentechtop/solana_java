package com.bit.solana;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.security.Security;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class Ed25519SingleThreadPerformance {
    // 单线程复用的签名器和验证器（无需ThreadLocal，单线程直接复用）
    private static final Ed25519Signer SIGNER = new Ed25519Signer();
    private static final Ed25519Signer VERIFIER = new Ed25519Signer();

    static {
        // 注册BouncyCastle Provider
        Security.addProvider(new BouncyCastleProvider());
    }

    // 单线程签名：直接复用同一个Signer实例
    public static byte[] fastSign(byte[] privateKey, byte[] data) {
        Ed25519PrivateKeyParameters keyParams = new Ed25519PrivateKeyParameters(privateKey, 0);
        SIGNER.init(true, keyParams);
        SIGNER.update(data, 0, data.length);
        return SIGNER.generateSignature();
    }

    // 单线程验签：直接复用同一个Verifier实例
    public static boolean fastVerify(byte[] publicKey, byte[] data, byte[] signature) {
        Ed25519PublicKeyParameters keyParams = new Ed25519PublicKeyParameters(publicKey, 0);
        VERIFIER.init(false, keyParams);
        VERIFIER.update(data, 0, data.length);
        return VERIFIER.verifySignature(signature);
    }

    // 生成测试数据
    private static void generateTestData(int count, List<byte[]> privateKeys, List<byte[]> publicKeys, List<byte[]> dataList) {
        Random random = new Random(42); // 固定种子，保证测试可重复
        for (int i = 0; i < count; i++) {
            // 32字节私钥
            byte[] privateKey = new byte[32];
            random.nextBytes(privateKey);
            privateKeys.add(privateKey);

            // 从私钥派生32字节公钥
            Ed25519PrivateKeyParameters privParams = new Ed25519PrivateKeyParameters(privateKey, 0);
            byte[] publicKey = privParams.generatePublicKey().getEncoded();
            publicKeys.add(publicKey);

            // 128字节待签名数据（模拟实际场景中的交易数据长度）
            byte[] data = new byte[128];
            random.nextBytes(data);
            dataList.add(data);
        }
    }

    public static void main(String[] args) {
        // 测试参数（单线程处理，数量不宜过大以免耗时过长）
        int testCount = 10000; // 总测试次数（可根据实际耗时调整）

        // 生成测试数据
        List<byte[]> privateKeys = new ArrayList<>(testCount);
        List<byte[]> publicKeys = new ArrayList<>(testCount);
        List<byte[]> dataList = new ArrayList<>(testCount);
        System.out.println("生成 " + testCount + " 条测试数据...");
        generateTestData(testCount, privateKeys, publicKeys, dataList);

        // 单线程签名性能测试
        System.out.println("\n===== 单线程签名测试 =====");
        List<byte[]> signatures = new ArrayList<>(testCount);
        long signStart = System.currentTimeMillis();
        for (int i = 0; i < testCount; i++) {
            byte[] signature = fastSign(privateKeys.get(i), dataList.get(i));
            signatures.add(signature);
        }
        long signEnd = System.currentTimeMillis();
        long signTimeMs = signEnd - signStart;
        double signPerSecond = testCount / (signTimeMs / 1000.0);
        System.out.printf("完成 %d 次签名，耗时 %d ms\n", testCount, signTimeMs);
        System.out.printf("单线程签名速度：%.2f 次/秒\n", signPerSecond);

        // 单线程验签性能测试
        System.out.println("\n===== 单线程验签测试 =====");
        long verifyStart = System.currentTimeMillis();
        int successCount = 0;
        for (int i = 0; i < testCount; i++) {
            boolean result = fastVerify(publicKeys.get(i), dataList.get(i), signatures.get(i));
            if (result) successCount++;
        }
        long verifyEnd = System.currentTimeMillis();
        long verifyTimeMs = verifyEnd - verifyStart;
        double verifyPerSecond = testCount / (verifyTimeMs / 1000.0);
        System.out.printf("完成 %d 次验签，耗时 %d ms，成功 %d 次\n", testCount, verifyTimeMs, successCount);
        System.out.printf("单线程验签速度：%.2f 次/秒\n", verifyPerSecond);
    }
}