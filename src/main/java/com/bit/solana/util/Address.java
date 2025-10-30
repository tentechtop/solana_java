package com.bit.solana.util;

import lombok.extern.slf4j.Slf4j;

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
     */
    public static boolean validateAddress(String address) {
        return false;
    }

    /**
     * 从地址恢复公钥
     */
    public static byte[] recoverPublicKeyHashFromAddress(String address) {
        return null;
    }

    // 测试方法
    public static void main(String[] args) {
        // 生成测试密钥对
        KeyPair keyPair = Ed25519Signer.generateKeyPair();
        PublicKey publicKey = keyPair.getPublic();
        PrivateKey privateKey = keyPair.getPrivate();
        log.info("公钥长度: {}",publicKey.getEncoded().length);
        log.info("私钥长度: {}",privateKey.getEncoded().length);



    }
}