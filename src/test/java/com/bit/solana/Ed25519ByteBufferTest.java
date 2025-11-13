package com.bit.solana;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Arrays;

public class Ed25519ByteBufferTest {
    // 线程局部变量：复用签名器和验证器
    private static final ThreadLocal<Ed25519Signer> SIGNER_THREAD_LOCAL = ThreadLocal.withInitial(Ed25519Signer::new);
    private static final ThreadLocal<Ed25519Signer> VERIFIER_THREAD_LOCAL = ThreadLocal.withInitial(Ed25519Signer::new);

    // 测试参数
    private static final int TEST_COUNT = 100_000; // 测试次数（10万次）
    private static final int DATA_SIZE = 1024;     // 数据大小（1KB）

    public static void main(String[] args) {
        // 1. 初始化测试数据
        SecureRandom random = new SecureRandom();
        byte[] privateKey = new byte[32];
        random.nextBytes(privateKey);
        Ed25519PrivateKeyParameters privParams = new Ed25519PrivateKeyParameters(privateKey, 0);
        byte[] publicKey = privParams.generatePublicKey().getEncoded();

        // 生成测试数据
        byte[] data = new byte[DATA_SIZE];
        random.nextBytes(data);
        ByteBuffer heapBuffer = ByteBuffer.wrap(data); // 堆缓冲区（复用data数组）
        ByteBuffer directBuffer = ByteBuffer.allocateDirect(DATA_SIZE); // 堆外缓冲区
        directBuffer.put(data).flip();

        // 预生成签名（用于验签测试）
        byte[] signature = fastSign(privateKey, data);
        if (!fastVerify(publicKey, data, signature)) {
            System.out.println("签名验证失败，测试环境异常！");
            return;
        }

        // 2. 测试签名性能
        testSignPerformance(privateKey, data, heapBuffer, directBuffer);

        // 3. 测试验签性能
        testVerifyPerformance(publicKey, data, signature, heapBuffer, directBuffer);
    }

    /**
     * 测试签名性能对比
     */
    private static void testSignPerformance(byte[] privateKey, byte[] data, ByteBuffer heapBuffer, ByteBuffer directBuffer) {
        System.out.println("\n===== 签名性能测试（" + TEST_COUNT + "次） =====");

        // 测试byte[]输入
        long start = System.nanoTime();
        for (int i = 0; i < TEST_COUNT; i++) {
            fastSign(privateKey, data);
        }
        long byteArrayTime = System.nanoTime() - start;
        printResult("byte[]输入", byteArrayTime);

        // 测试堆ByteBuffer输入
        start = System.nanoTime();
        for (int i = 0; i < TEST_COUNT; i++) {
            heapBuffer.position(0); // 重置指针
            fastSign(privateKey, heapBuffer);
        }
        long heapBufferTime = System.nanoTime() - start;
        printResult("堆ByteBuffer输入", heapBufferTime);

        // 测试堆外ByteBuffer输入
        start = System.nanoTime();
        for (int i = 0; i < TEST_COUNT; i++) {
            directBuffer.position(0); // 重置指针
            fastSign(privateKey, directBuffer);
        }
        long directBufferTime = System.nanoTime() - start;
        printResult("堆外ByteBuffer输入", directBufferTime);

        // 计算性能提升百分比
        double heapImprove = (1 - (double) heapBufferTime / byteArrayTime) * 100;
        double directImprove = (1 - (double) directBufferTime / byteArrayTime) * 100;
        System.out.printf("堆ByteBuffer比byte[]快: %.2f%%\n", heapImprove);
        System.out.printf("堆外ByteBuffer比byte[]快: %.2f%%\n", directImprove);
    }

    /**
     * 测试验签性能对比
     */
    private static void testVerifyPerformance(byte[] publicKey, byte[] data, byte[] signature, ByteBuffer heapBuffer, ByteBuffer directBuffer) {
        System.out.println("\n===== 验签性能测试（" + TEST_COUNT + "次） =====");

        // 测试byte[]输入
        long start = System.nanoTime();
        for (int i = 0; i < TEST_COUNT; i++) {
            fastVerify(publicKey, data, signature);
        }
        long byteArrayTime = System.nanoTime() - start;
        printResult("byte[]输入", byteArrayTime);

        // 测试堆ByteBuffer输入
        start = System.nanoTime();
        for (int i = 0; i < TEST_COUNT; i++) {
            heapBuffer.position(0); // 重置指针
            fastVerify(publicKey, heapBuffer, signature);
        }
        long heapBufferTime = System.nanoTime() - start;
        printResult("堆ByteBuffer输入", heapBufferTime);

        // 测试堆外ByteBuffer输入
        start = System.nanoTime();
        for (int i = 0; i < TEST_COUNT; i++) {
            directBuffer.position(0); // 重置指针
            fastVerify(publicKey, directBuffer, signature);
        }
        long directBufferTime = System.nanoTime() - start;
        printResult("堆外ByteBuffer输入", directBufferTime);

        // 计算性能提升百分比
        double heapImprove = (1 - (double) heapBufferTime / byteArrayTime) * 100;
        double directImprove = (1 - (double) directBufferTime / byteArrayTime) * 100;
        System.out.printf("堆ByteBuffer比byte[]快: %.2f%%\n", heapImprove);
        System.out.printf("堆外ByteBuffer比byte[]快: %.2f%%\n", directImprove);
    }

    /**
     * 打印测试结果
     */
    private static void printResult(String type, long nanoTime) {
        double msTime = nanoTime / 1_000_000.0; // 转换为毫秒
        double opsPerSecond = TEST_COUNT / (nanoTime / 1_000_000_000.0); // 每秒操作数
        System.out.printf("%s: 耗时=%.2fms, 每秒操作数=%.0f\n", type, msTime, opsPerSecond);
    }

    // ------------------------------ 签名/验签方法（复用之前的实现） ------------------------------

    public static byte[] fastSign(byte[] privateKey, byte[] data) {
        Ed25519Signer signer = SIGNER_THREAD_LOCAL.get();
        Ed25519PrivateKeyParameters keyParams = new Ed25519PrivateKeyParameters(privateKey, 0);
        signer.init(true, keyParams);
        signer.update(data, 0, data.length);
        return signer.generateSignature();
    }

    public static byte[] fastSign(byte[] privateKey, ByteBuffer data) {
        Ed25519Signer signer = SIGNER_THREAD_LOCAL.get();
        Ed25519PrivateKeyParameters keyParams = new Ed25519PrivateKeyParameters(privateKey, 0);
        signer.init(true, keyParams);

        if (data.hasArray()) {
            // 堆缓冲区：直接使用底层数组（无拷贝）
            byte[] array = data.array();
            int offset = data.arrayOffset() + data.position();
            int length = data.remaining();
            signer.update(array, offset, length);
        } else {
            // 堆外缓冲区：使用ThreadLocal缓存的临时数组
            ThreadLocal<byte[]> tempCache = ThreadLocal.withInitial(() -> new byte[4096]);
            byte[] temp = tempCache.get();
            int remaining = data.remaining();
            while (remaining > 0) {
                int readLen = Math.min(remaining, temp.length);
                data.get(temp, 0, readLen);
                signer.update(temp, 0, readLen);
                remaining -= readLen;
            }
        }
        return signer.generateSignature();
    }

    public static boolean fastVerify(byte[] publicKey, byte[] data, byte[] signature) {
        Ed25519Signer verifier = VERIFIER_THREAD_LOCAL.get();
        Ed25519PublicKeyParameters keyParams = new Ed25519PublicKeyParameters(publicKey, 0);
        verifier.init(false, keyParams);
        verifier.update(data, 0, data.length);
        return verifier.verifySignature(signature);
    }

    public static boolean fastVerify(byte[] publicKey, ByteBuffer data, byte[] signature) {
        Ed25519Signer verifier = VERIFIER_THREAD_LOCAL.get();
        Ed25519PublicKeyParameters keyParams = new Ed25519PublicKeyParameters(publicKey, 0);
        verifier.init(false, keyParams);

        if (data.hasArray()) {
            // 堆缓冲区：直接使用底层数组（无拷贝）
            byte[] array = data.array();
            int offset = data.arrayOffset() + data.position();
            int length = data.remaining();
            verifier.update(array, offset, length);
        } else {
            // 堆外缓冲区：使用ThreadLocal缓存的临时数组
            ThreadLocal<byte[]> tempCache = ThreadLocal.withInitial(() -> new byte[4096]);
            byte[] temp = tempCache.get();
            int remaining = data.remaining();
            while (remaining > 0) {
                int readLen = Math.min(remaining, temp.length);
                data.get(temp, 0, readLen);
                verifier.update(temp, 0, readLen);
                remaining -= readLen;
            }
        }
        return verifier.verifySignature(signature);
    }
}