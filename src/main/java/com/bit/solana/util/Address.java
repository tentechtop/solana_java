package com.bit.solana.util;

import com.bit.solana.netversion.NetVersion;
import lombok.extern.slf4j.Slf4j;

import java.security.PublicKey;


/**
 * Solana地址工具类
 * 核心逻辑：基于Ed25519公钥 + 网络前缀，通过Base58Check编码生成地址，支持反向解析与验证
 * Solana 的官方地址是44 个 Base58Check 编码字符，对应37 字节的二进制数据
 */
@Slf4j
public class Address {

    /**
     * 1. 生成Solana地址（核心方法）
     * 逻辑：网络前缀(1字节) + 公钥(32字节) → 拼接后Base58Check编码
     * @param publicKey Ed25519公钥（从Ed25519Signer.generateKeyPair()获取）
     * @param netVersion 网络版本（主网/测试网/开发网，来自NetVersion枚举）
     * @return 标准Solana地址（Base58Check编码字符串）
     */
    public static String generateAddress(PublicKey publicKey, NetVersion netVersion) {

        return null;
    }

    /**
     * 2. 验证Solana地址有效性
     * 校验维度：Base58格式、校验和、长度、网络前缀、公钥合法性
     * @param address 待验证的Solana地址
     * @return true=有效地址，false=无效地址
     */
    public static boolean validateAddress(String address) {
        return false;
    }

    /**
     * 3. 从地址恢复公钥Hash（即32字节Ed25519公钥核心字节）
     * @param address 已验证的Solana地址
     * @return 32字节公钥Hash，地址无效则返回null
     */
    public static byte[] recoverPublicKeyHashFromAddress(String address) {

        return null;
    }

    /**
     * 4. 从地址获取所属网络
     * @param address 已验证的Solana地址
     * @return 网络版本（NetVersion枚举），地址无效则返回null
     */
    public static NetVersion getNetVersionFromAddress(String address) {

        return null;
    }
}