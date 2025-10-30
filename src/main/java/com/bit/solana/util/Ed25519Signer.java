package com.bit.solana.util;

import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;


@Slf4j
public class Ed25519Signer {

    // 静态代码块：注册BouncyCastle Provider（优先于系统默认）
    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    /**
     * 生成 Ed25519 密钥对
     * @return 包含公钥（32字节）和私钥（32字节）的 KeyPair
     */
    public static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("Ed25519");
            return keyPairGenerator.generateKeyPair(); // 直接生成
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Ed25519 密钥对生成失败", e);
        }
    }

    /**
     * Ed25519 签名：用私钥对原始数据签名（返回 64 字节签名）
     * @param privateKey Ed25519 私钥
     * @param data 待签名原始数据（如交易哈希）
     * @return 签名结果（64字节）
     */
    public static byte[] applySignature(PrivateKey privateKey, byte[] data) {
        try {
            Signature signer;
            // 优先 JDK 原生，降级 BouncyCastle
            try {
                signer = Signature.getInstance("Ed25519");
            } catch (NoSuchAlgorithmException e) {
                signer = Signature.getInstance("Ed25519", "BC");
            }
            signer.initSign(privateKey);
            signer.update(data); // Ed25519 内部自动处理哈希（SHA-512），无需手动哈希
            return signer.sign();
        } catch (Exception e) {
            throw new RuntimeException("Ed25519 签名失败", e);
        }
    }

    /**
     * Ed25519 验签：用公钥验证签名有效性
     * @param publicKey Ed25519 公钥
     * @param data 原始数据（需与签名时一致）
     * @param signature 签名结果（64字节）
     * @return true=验签成功，false=验签失败
     */
    public static boolean verifySignature(PublicKey publicKey, byte[] data, byte[] signature) {
        try {
            Signature verifier;
            // 优先 JDK 原生，降级 BouncyCastle
            try {
                verifier = Signature.getInstance("Ed25519");
            } catch (NoSuchAlgorithmException e) {
                verifier = Signature.getInstance("Ed25519", "BC");
            }
            verifier.initVerify(publicKey);
            verifier.update(data);
            return verifier.verify(signature);
        } catch (Exception e) {
            log.error("Ed25519 验签异常", e);
            return false;
        }
    }

    /**
     * 字节数组转 Ed25519 公钥（X.509 编码，与 secp256k1 公钥编码格式一致）
     * @param publicKeyBytes X.509 编码的公钥字节（32字节核心+编码头）
     * @return Ed25519 公钥对象
     */
    public static PublicKey bytesToPublicKey(byte[] publicKeyBytes) {
        try {
            KeyFactory keyFactory;
            try {
                keyFactory = KeyFactory.getInstance("Ed25519");
            } catch (NoSuchAlgorithmException e) {
                keyFactory = KeyFactory.getInstance("Ed25519", "BC");
            }
            // 处理32字节原始公钥：补全X.509固定头（12字节）
            byte[] x509Bytes;
            if (publicKeyBytes.length == 32) {
                // X.509固定头（十六进制转字节：302a300506032b6570032100）
                byte[] header = new byte[]{
                        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00
                };
                x509Bytes = new byte[header.length + publicKeyBytes.length];
                System.arraycopy(header, 0, x509Bytes, 0, header.length);
                System.arraycopy(publicKeyBytes, 0, x509Bytes, header.length, publicKeyBytes.length);
            } else {
                // 已为X.509编码（44字节），直接使用
                x509Bytes = publicKeyBytes;
            }

            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(x509Bytes);
            return keyFactory.generatePublic(keySpec);
        } catch (Exception e) {
            throw new RuntimeException("Ed25519 公钥转换失败", e);
        }
    }



    /**
     * 字节数组转 Ed25519 私钥（PKCS#8 编码，与 secp256k1 私钥编码格式一致）
     * @param privateKeyBytes PKCS#8 编码的私钥字节（32字节核心+编码头）
     * @return Ed25519 私钥对象
     */
    public static PrivateKey bytesToPrivateKey(byte[] privateKeyBytes) {
        try {
            KeyFactory keyFactory;
            try {
                keyFactory = KeyFactory.getInstance("Ed25519");
            } catch (NoSuchAlgorithmException e) {
                keyFactory = KeyFactory.getInstance("Ed25519", "BC");
            }
            PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(privateKeyBytes);
            return keyFactory.generatePrivate(keySpec);
        } catch (Exception e) {
            throw new RuntimeException("Ed25519 私钥转换失败", e);
        }
    }

}
