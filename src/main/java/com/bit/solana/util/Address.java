package com.bit.solana.util;


import lombok.extern.slf4j.Slf4j;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.util.Arrays;

@Slf4j
public class Address {

    // Base58Check校验和长度（4字节）
    private static final int CHECKSUM_LENGTH = 4;
    // 地址解码后总长度（32字节公钥 + 4字节校验和）
    private static final int DECODED_ADDRESS_LENGTH = 32 + CHECKSUM_LENGTH;
    // 标准Solana地址长度（Base58编码后）
    private static final int STANDARD_ADDRESS_LENGTH = 44;

    /**
     * 生成Solana地址（无网络前缀）
     * 逻辑：32字节公钥 → 计算校验和 → 公钥+校验和拼接后Base58编码
     * @param publicKey Ed25519公钥
     * @return 标准Solana地址（Base58编码字符串）
     */
    public static String generateAddress(PublicKey publicKey) {
        if (publicKey == null) {
            log.error("生成地址失败：公钥为空");
            return null;
        }
        // 1. 提取32字节原始公钥（从X.509编码中提取核心部分）
        byte[] encodedPubKey = publicKey.getEncoded();
        byte[] pubKeyBytes;

        // Ed25519公钥X.509编码通常为44字节（12字节头 + 32字节核心）
        if (encodedPubKey.length == 44) {
            pubKeyBytes = Arrays.copyOfRange(encodedPubKey, 12, 44); // 提取32字节核心
        } else if (encodedPubKey.length == 32) {
            pubKeyBytes = encodedPubKey; // 已为原始32字节公钥
        } else {
            log.error("公钥无效，长度必须为32或44字节（实际：{}字节）", encodedPubKey.length);
            return null;
        }


        return null;
    }

    /**
     * 验证Solana地址有效性（无网络前缀校验）
     * 校验维度：长度、Base58格式、解码后长度、校验和
     * @param address 待验证的Solana地址
     * @return true=有效地址，false=无效地址
     */
    public static boolean validateAddress(String address) {
        return false;
    }

    /**
     * 从地址恢复（32字节原始公钥）
     * @param address 已验证的Solana地址
     * @return 32字节公钥Hash，地址无效则返回null
     */
    public static byte[] recoverPublicKeyHashFromAddress(String address) {
        return null;
    }



    // 测试主方法
    public static void main(String[] args) {

    }
}