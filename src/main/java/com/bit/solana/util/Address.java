package com.bit.solana.util;

import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.Base58;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Arrays;

import static com.bit.solana.util.Ed25519Signer.extractPublicKeyCore;


@Slf4j
public class Address {

    /**
     * 生成标准Solana地址（44字符）
     */
    public static String generateAddress(PublicKey publicKey) {
        if (publicKey == null) {
            log.error("生成地址失败：公钥为空");
            return null;
        }
        byte[] corePub = extractPublicKeyCore(publicKey);
        return Base58.encode(corePub);
    }
    public static String generateAddress(byte[] corePub) {
        if (corePub.length != 32) {
            log.error("生成地址失败：公钥核心必须是32字节");
            return null;
        }
        return Base58.encode(corePub);
    }

    /**
     * 验证地址有效性
     * Solana地址规则：Base58编码后长度为44字符，解码后必须是32字节
     */
    public static boolean validateAddress(String address) {
        // 基础校验：非空且长度为44
        if (address == null || address.trim().isEmpty() || address.length() != 44) {
            log.debug("地址无效：为空或长度不是44字符");
            return false;
        }

        try {
            // 尝试Base58解码
            byte[] decoded = Base58.decode(address);
            // 解码后必须是32字节（Ed25519公钥长度）
            if (decoded.length == 32) {
                return true;
            } else {
                log.debug("地址无效：解码后长度为{}字节（应为32字节）", decoded.length);
                return false;
            }
        } catch (IllegalArgumentException e) {
            // Base58解码失败（包含无效字符）
            log.debug("地址无效：包含非Base58字符", e);
            return false;
        }
    }

    /**
     * 从地址恢复原始32字节公钥
     */
    public static byte[] recoverPublicKeyHashFromAddress(String address) {
        if (!validateAddress(address)) {
            log.error("恢复公钥失败：地址无效");
            return null;
        }

        try {
            // 地址有效时直接解码
            return Base58.decode(address);
        } catch (IllegalArgumentException e) {
            // 理论上不会走到这里（已通过validateAddress校验）
            log.error("恢复公钥失败：解码异常", e);
            return null;
        }
    }

    // 测试方法
    public static void main(String[] args) {
        // 生成测试密钥对
        KeyPair keyPair = Ed25519Signer.generateKeyPair();
        PublicKey publicKey = keyPair.getPublic();
        PrivateKey privateKey = keyPair.getPrivate();
        // 提取32字节核心公钥
        byte[] corePub = extractPublicKeyCore(publicKey);
        log.info("核心公钥长度: {}", corePub.length);

        // 生成地址
        String address = generateAddress(publicKey);
        log.info("生成的Solana地址: {}", address);
        log.info("地址长度: {}", address == null ? 0 : address.length());

        // 验证地址
        boolean isValid = validateAddress(address);
        log.info("地址验证结果: {}", isValid);

        // 从地址恢复公钥
        byte[] recoveredPub = recoverPublicKeyHashFromAddress(address);
        log.info("恢复的公钥与原始公钥是否一致: {}", Arrays.equals(corePub, recoveredPub));
    }
}