package com.bit.solana.util;

import com.bit.solana.structure.key.KeyInfo;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.Base58;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.util.encoders.Hex;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.List;


import static com.bit.solana.util.Ed25519HDWallet.generateMnemonic;
import static com.bit.solana.util.Ed25519HDWallet.getSolanaKeyPair;
import static org.bitcoinj.crypto.HDKeyDerivation.deriveChildKey;


@Slf4j
public class Ed25519Signer {
    // 静态代码块：注册BouncyCastle Provider（优先于系统默认）
    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    // Ed25519核心密钥长度（公钥/私钥均为32字节）
    public static final int CORE_KEY_LENGTH = 32;
    // X.509公钥编码头部长度（固定12字节）
    private static final int X509_HEADER_LENGTH = 12;
    // PKCS#8私钥编码头部长度（固定16字节）
    private static final int PKCS8_HEADER_LENGTH = 16;
    // X.509公钥固定头部（用于补全32字节核心公钥）
    private static final byte[] X509_PUBLIC_HEADER = new byte[]{
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00
    };
    // PKCS#8私钥固定头部（用于补全32字节核心私钥）
    private static final byte[] PKCS8_PRIVATE_HEADER = new byte[]{
            0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x04, 0x22, 0x04, 0x20
    };


    //根据助记词生成私钥  从私钥恢复公钥 HD 分层确定性


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


    // ------------------------------ 公钥核心字节处理 ------------------------------

    /**
     * 从32字节核心公钥恢复公钥对象（自动补全X.509头部）
     * @param corePublicKey 32字节核心公钥
     * @return Ed25519公钥对象
     */
    public static PublicKey recoverPublicKeyFromCore(byte[] corePublicKey) {
        // 验证核心字节长度
        if (corePublicKey.length != CORE_KEY_LENGTH) {
            throw new IllegalArgumentException("核心公钥必须为32字节");
        }
        try {
            // 拼接X.509头部和核心字节，生成完整编码
            byte[] x509Encoded = new byte[X509_HEADER_LENGTH + CORE_KEY_LENGTH];
            System.arraycopy(X509_PUBLIC_HEADER, 0, x509Encoded, 0, X509_HEADER_LENGTH);
            System.arraycopy(corePublicKey, 0, x509Encoded, X509_HEADER_LENGTH, CORE_KEY_LENGTH);

            // 生成公钥对象
            KeyFactory keyFactory = getKeyFactory();
            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(x509Encoded);
            return keyFactory.generatePublic(keySpec);
        } catch (Exception e) {
            throw new RuntimeException("从核心公钥恢复公钥失败", e);
        }
    }


    // ------------------------------ 私钥核心字节处理 ------------------------------

    /**
     * 从私钥对象中提取32字节核心字节（剔除PKCS#8头部）
     * @param privateKey Ed25519私钥对象
     * @return 32字节核心私钥
     */
    public static byte[] extractPrivateKeyCore(PrivateKey privateKey) {
        byte[] encoded = privateKey.getEncoded();
        // 验证编码长度（PKCS#8编码私钥应为48字节：16字节头 + 32字节核心）
        if (encoded.length != PKCS8_HEADER_LENGTH + CORE_KEY_LENGTH) {
            throw new IllegalArgumentException("无效的Ed25519私钥编码，长度应为48字节");
        }
        // 截取后32字节作为核心私钥
        byte[] core = new byte[CORE_KEY_LENGTH];
        System.arraycopy(encoded, PKCS8_HEADER_LENGTH, core, 0, CORE_KEY_LENGTH);
        return core;
    }

    /**
     * 从32字节核心私钥恢复私钥对象（自动补全PKCS#8头部）
     * @param corePrivateKey 32字节核心私钥
     * @return Ed25519私钥对象
     */
    public static PrivateKey recoverPrivateKeyFromCore(byte[] corePrivateKey) {
        // 验证核心字节长度
        if (corePrivateKey.length != CORE_KEY_LENGTH) {
            throw new IllegalArgumentException("核心私钥必须为32字节");
        }
        try {
            // 拼接PKCS#8头部和核心字节，生成完整编码
            byte[] pkcs8Encoded = new byte[PKCS8_HEADER_LENGTH + CORE_KEY_LENGTH];
            System.arraycopy(PKCS8_PRIVATE_HEADER, 0, pkcs8Encoded, 0, PKCS8_HEADER_LENGTH);
            System.arraycopy(corePrivateKey, 0, pkcs8Encoded, PKCS8_HEADER_LENGTH, CORE_KEY_LENGTH);

            // 生成私钥对象
            KeyFactory keyFactory = getKeyFactory();
            PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(pkcs8Encoded);
            return keyFactory.generatePrivate(keySpec);
        } catch (Exception e) {
            throw new RuntimeException("从核心私钥恢复私钥失败", e);
        }
    }


    // ------------------------------ 工具方法 ------------------------------

    /**
     * 获取Ed25519密钥工厂（优先JDK原生，降级BouncyCastle）
     */
    private static KeyFactory getKeyFactory() throws NoSuchAlgorithmException, NoSuchProviderException {
        try {
            return KeyFactory.getInstance("Ed25519");
        } catch (NoSuchAlgorithmException e) {
            return KeyFactory.getInstance("Ed25519", "BC");
        }
    }



    // 在 Ed25519Signer 中添加：
    /**
     * 从 Ed25519 私钥（32字节）派生公钥（32字节）
     * 核心逻辑：使用 Ed25519 算法从私钥计算公钥（非对称加密的核心步骤）
     */
    public static byte[] derivePublicKeyFromPrivateKey(byte[] privateKey) {
        if (privateKey.length != CORE_KEY_LENGTH) {
            throw new IllegalArgumentException("私钥必须为32字节");
        }
        // 使用 BouncyCastle 的 Ed25519 实现派生公钥
        Ed25519PrivateKeyParameters privateKeyParams = new Ed25519PrivateKeyParameters(privateKey, 0);
        Ed25519PublicKeyParameters publicKeyParams = privateKeyParams.generatePublicKey();
        return publicKeyParams.getEncoded();
    }




    // ------------------------------ 测试方法 ------------------------------

    public static void main(String[] args) {

        // 生成助记词
        List<String> mnemonic = generateMnemonic();
        System.out.println("助记词: " + String.join(" ", mnemonic));
        // 生成第一个账户的第一个地址（路径：m/44'/501'/0'/0/0）
        KeyInfo keyInfo = getSolanaKeyPair(mnemonic, 0, 0);


        // 输出信息
        System.out.println("派生路径: " + keyInfo.getPath());
        System.out.println("私钥(hex): " + Hex.toHexString(keyInfo.getPrivateKey()));
        System.out.println("公钥(hex): " + Hex.toHexString(keyInfo.getPublicKey()));
        System.out.println("Solana地址: " + keyInfo.getAddress());


        // 2. 提取核心字节
        byte[] corePriv = keyInfo.getPrivateKey();
        byte[] corePub = derivePublicKeyFromPrivateKey(corePriv);

        log.info("公钥: {}", corePub);
        String encode = Base58.encode(corePub);
        log.info("公钥编码: {}", encode.length());
        log.info("地址: {}", encode);

        //从地址到公钥

        byte[] decode = Base58.decode(encode);
        log.info("是否一致，{}", Arrays.equals(decode, corePub));


        log.info("提取的核心公钥长度: {}", corePub.length); // 32字节
        log.info("提取的核心私钥长度: {}", corePriv.length); // 32字节

        // 3. 从核心字节恢复密钥
        PublicKey recoveredPub = recoverPublicKeyFromCore(corePub);
        PrivateKey recoveredPriv = recoverPrivateKeyFromCore(corePriv);

        // 4. 验证恢复的密钥是否可用（签名+验签）
        byte[] data = "测试数据".getBytes(StandardCharsets.UTF_8);
        byte[] signature = applySignature(recoveredPriv, data);
        boolean verifyResult = verifySignature(recoveredPub, data, signature);
        log.info("恢复的密钥验签结果: {}", verifyResult); // 应输出true
    }


}
