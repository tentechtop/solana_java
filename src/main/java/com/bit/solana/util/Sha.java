package com.bit.solana.util;

import org.bouncycastle.crypto.digests.SHA512Digest;
import org.bouncycastle.crypto.macs.HMac;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.jcajce.provider.digest.Keccak;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.security.MessageDigest;
import java.security.Security;

public class Sha {
    // 静态代码块：确保BouncyCastle先注册
    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    /**
     * 修复：强制用BouncyCastle的SHA-256，避免混合实现导致哈希结果不一致
     */
    public static byte[] applySHA256(byte[] data) {
        try {
            // 直接使用BouncyCastle的SHA-256实现，不降级
            MessageDigest digest = MessageDigest.getInstance("SHA-256", BouncyCastleProvider.PROVIDER_NAME);
            return digest.digest(data);
        } catch (Exception e) {
            // 此时异常为致命错误（非降级场景），直接抛出
            throw new RuntimeException("BouncyCastle SHA-256初始化失败，检查依赖是否正确", e);
        }
    }

    /**
     * 添加SHA-512哈希实现（使用BouncyCastle）
     */
    public static byte[] applySHA512(byte[] data) {
        try {
            // 优先使用BouncyCastle的SHA-512实现，保证一致性
            MessageDigest digest = MessageDigest.getInstance("SHA-512", BouncyCastleProvider.PROVIDER_NAME);
            return digest.digest(data);
        } catch (Exception e) {
            throw new RuntimeException("BouncyCastle SHA-512初始化失败，检查依赖是否正确", e);
        }
    }

    public static byte[] applySha3(byte[] data) {
        Keccak.DigestKeccak kecc = new Keccak.Digest256();
        kecc.update(data, 0, data.length);
        return kecc.digest();
    }






}
