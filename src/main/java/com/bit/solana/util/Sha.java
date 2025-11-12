package com.bit.solana.util;

import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.jcajce.provider.digest.Keccak;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.security.MessageDigest;
import java.security.Security;

@Slf4j
public class Sha {
    // 静态代码块：确保BouncyCastle先注册
    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        // 提前验证BouncyCastle是否可用
        try {
            BouncyCastleProvider provider = (BouncyCastleProvider) Security.getProvider(BouncyCastleProvider.PROVIDER_NAME);
            if (provider == null) {
                throw new IllegalStateException("BouncyCastleProvider未注册，请检查依赖");
            }
            // 验证核心算法是否支持
            MessageDigest.getInstance("SHA-256", provider);
            MessageDigest.getInstance("SHA-512", provider);
        } catch (Exception e) {
            throw new RuntimeException("哈希算法初始化验证失败：" + e.getMessage()
                    + "，请确保BouncyCastle依赖正确（建议版本1.68+）", e);
        }
    }

    // ThreadLocal存储每个线程独立的SHA-256实例
    private static final ThreadLocal<MessageDigest> SHA256_THREAD_LOCAL = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256", BouncyCastleProvider.PROVIDER_NAME);
        } catch (Exception e) {
            throw new RuntimeException("创建线程本地SHA-256实例失败", e);
        }
    });

    // ThreadLocal存储每个线程独立的SHA-512实例
    private static final ThreadLocal<MessageDigest> SHA512_THREAD_LOCAL = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-512", BouncyCastleProvider.PROVIDER_NAME);
        } catch (Exception e) {
            throw new RuntimeException("创建线程本地SHA-512实例失败", e);
        }
    });

    // ThreadLocal存储每个线程独立的SHA3-256实例
    private static final ThreadLocal<Keccak.DigestKeccak> SHA3_THREAD_LOCAL = ThreadLocal.withInitial(() -> {
        return new Keccak.Digest256();
    });

    /**
     * 线程安全的SHA-256计算（每个线程复用自己的实例）
     */
    public static byte[] applySHA256(byte[] data) {
        if (data == null) {
            throw new IllegalArgumentException("输入数据不能为空");
        }
        MessageDigest digest = SHA256_THREAD_LOCAL.get();
        digest.reset();
        return digest.digest(data);
    }

    /**
     * 批量计算SHA-256哈希（线程安全）
     */
    public static byte[][] batchApplySHA256(byte[][] dataList) {
        if (dataList == null || dataList.length == 0) {
            return new byte[0][];
        }
        MessageDigest digest = SHA256_THREAD_LOCAL.get();
        byte[][] results = new byte[dataList.length][];
        for (int i = 0; i < dataList.length; i++) {
            if (dataList[i] == null) {
                throw new IllegalArgumentException("批量输入中第" + i + "个数据为null");
            }
            digest.reset();
            results[i] = digest.digest(dataList[i]);
        }
        return results;
    }

    /**
     * 线程安全的SHA-512计算
     */
    public static byte[] applySHA512(byte[] data) {
        if (data == null) {
            throw new IllegalArgumentException("输入数据不能为空");
        }
        MessageDigest digest = SHA512_THREAD_LOCAL.get();
        digest.reset();
        return digest.digest(data);
    }

    /**
     * 线程安全的SHA3-256计算
     */
    public static byte[] applySha3(byte[] data) {
        if (data == null) {
            throw new IllegalArgumentException("输入数据不能为空");
        }
        Keccak.DigestKeccak digest = SHA3_THREAD_LOCAL.get();
        digest.reset();
        digest.update(data, 0, data.length);
        return digest.digest();
    }

    /**
     * 手动清理线程本地资源（建议在线程池任务结束时调用）
     */
    public static void clearThreadLocals() {
        SHA256_THREAD_LOCAL.remove();
        SHA512_THREAD_LOCAL.remove();
        SHA3_THREAD_LOCAL.remove();
    }
}