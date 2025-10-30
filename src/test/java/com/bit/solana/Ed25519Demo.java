package com.bit.solana;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Base64;

public class Ed25519Demo {
    public static void main(String[] args) {
        // 1. 待签名的原始数据
        String originalData = "test msg";

        try {
            // 2. 生成Ed25519密钥对
            KeyPairGenerator ed25519Generator = KeyPairGenerator.getInstance("Ed25519");
            KeyPair keyPair = ed25519Generator.generateKeyPair();
            PublicKey publicKey = keyPair.getPublic();
            PrivateKey privateKey = keyPair.getPrivate();

            // 3. 私钥签名
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(privateKey);
            signer.update(originalData.getBytes(StandardCharsets.UTF_8));
            byte[] signatureBytes = signer.sign();
            String signatureBase64 = Base64.getEncoder().encodeToString(signatureBytes);
            System.out.println("签名结果（Base64）：" + signatureBase64);

            // 4. 公钥验签
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(originalData.getBytes(StandardCharsets.UTF_8));
            boolean verifyResult = verifier.verify(Base64.getDecoder().decode(signatureBase64));
            System.out.println("验签结果：" + verifyResult);

        } catch (NoSuchAlgorithmException e) {
            // 异常：Java版本过低或算法名称错误
            System.err.println("不支持Ed25519算法，请使用Java 15及以上版本！");
            e.printStackTrace();
        } catch (InvalidKeyException e) {
            // 异常：密钥非法（如私钥损坏、公钥不匹配）
            System.err.println("密钥非法，初始化签名/验签失败！");
            e.printStackTrace();
        } catch (SignatureException e) {
            // 异常：签名过程错误（如未初始化就调用update/sign）
            System.err.println("签名/验签过程异常！");
            e.printStackTrace();
        }
    }
}
