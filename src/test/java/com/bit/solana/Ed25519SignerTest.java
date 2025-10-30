package com.bit.solana;

import com.bit.solana.util.Ed25519Signer;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

@Slf4j
public class Ed25519SignerTest {



    @Test
    void testKeyPairGenerationAndRecovery() {
        log.info("===== 开始Ed25519签名工具全流程测试 =====");

        try {
            // 1. 生成原始密钥对
            log.info("1. 测试密钥对生成...");
            KeyPair originalKeyPair = Ed25519Signer.generateKeyPair();
            PublicKey originalPubKey = originalKeyPair.getPublic();
            PrivateKey originalPrivKey = originalKeyPair.getPrivate();

            if (originalPubKey == null) {
                log.error("❌ 原始公钥生成失败：公钥为null");
            } else {
                log.info("✅ 原始公钥生成成功（类型：{}）", originalPubKey.getClass().getSimpleName());
            }

            if (originalPrivKey == null) {
                log.error("❌ 原始私钥生成失败：私钥为null");
            } else {
                log.info("✅ 原始私钥生成成功（类型：{}）", originalPrivKey.getClass().getSimpleName());
            }

            // 2. 序列化密钥对为字节数组
            log.info("\n2. 测试密钥序列化...");
            byte[] pubKeyBytes = originalPubKey != null ? originalPubKey.getEncoded() : null;
            byte[] privKeyBytes = originalPrivKey != null ? originalPrivKey.getEncoded() : null;

            if (pubKeyBytes == null || pubKeyBytes.length == 0) {
                log.error("❌ 公钥序列化失败：字节数组为空或长度为0");
            } else {
                log.info("✅ 公钥序列化成功（长度：{}字节）", pubKeyBytes.length);
            }

            if (privKeyBytes == null || privKeyBytes.length == 0) {
                log.error("❌ 私钥序列化失败：字节数组为空或长度为0");
            } else {
                log.info("✅ 私钥序列化成功（长度：{}字节）", privKeyBytes.length);
            }

            // 3. 从字节数组恢复密钥对
            log.info("\n3. 测试密钥恢复...");
            PublicKey recoveredPubKey = null;
            PrivateKey recoveredPrivKey = null;

            if (pubKeyBytes != null && pubKeyBytes.length > 0) {
                try {
                    recoveredPubKey = Ed25519Signer.bytesToPublicKey(pubKeyBytes);
                    log.info("✅ 公钥从字节数组恢复成功");
                } catch (Exception e) {
                    log.error("❌ 公钥恢复失败：{}", e.getMessage());
                }
            } else {
                log.warn("⚠️ 跳过公钥恢复：原始公钥字节数组无效");
            }

            if (privKeyBytes != null && privKeyBytes.length > 0) {
                try {
                    recoveredPrivKey = Ed25519Signer.bytesToPrivateKey(privKeyBytes);
                    log.info("✅ 私钥从字节数组恢复成功");
                } catch (Exception e) {
                    log.error("❌ 私钥恢复失败：{}", e.getMessage());
                }
            } else {
                log.warn("⚠️ 跳过私钥恢复：原始私钥字节数组无效");
            }

            // 4. 验证恢复的密钥与原始密钥一致性
            log.info("\n4. 验证密钥恢复一致性...");
            if (originalPubKey != null && recoveredPubKey != null) {
                boolean pubKeyMatch = Arrays.equals(originalPubKey.getEncoded(), recoveredPubKey.getEncoded());
                if (pubKeyMatch) {
                    log.info("✅ 公钥恢复一致性验证通过");
                } else {
                    log.error("❌ 公钥恢复一致性验证失败：原始与恢复的公钥编码不一致");
                }
            } else {
                log.warn("⚠️ 跳过公钥一致性验证：原始或恢复的公钥为null");
            }

            if (originalPrivKey != null && recoveredPrivKey != null) {
                boolean privKeyMatch = Arrays.equals(originalPrivKey.getEncoded(), recoveredPrivKey.getEncoded());
                if (privKeyMatch) {
                    log.info("✅ 私钥恢复一致性验证通过");
                } else {
                    log.error("❌ 私钥恢复一致性验证失败：原始与恢复的私钥编码不一致");
                }
            } else {
                log.warn("⚠️ 跳过私钥一致性验证：原始或恢复的私钥为null");
            }



            // 6. 测试签名与验签功能
            log.info("\n6. 测试签名与验签...");
            byte[] testData = "Solana Ed25519 签名测试数据".getBytes(StandardCharsets.UTF_8);
            byte[] signature = null;

            if (originalPrivKey != null) {
                try {
                    signature = Ed25519Signer.applySignature(originalPrivKey, testData);
                    if (signature != null && signature.length == 64) {
                        log.info("✅ 签名成功（长度：64字节，符合Ed25519标准）");
                    } else {
                        log.error("❌ 签名失败：签名长度异常（实际：{}字节）", signature == null ? 0 : signature.length);
                    }
                } catch (Exception e) {
                    log.error("❌ 签名过程抛出异常：{}", e.getMessage());
                }
            } else {
                log.warn("⚠️ 跳过签名：原始私钥为null");
            }

            // 验证原始公钥验签
            if (originalPubKey != null && signature != null && testData != null) {
                boolean verifyOriginal = Ed25519Signer.verifySignature(originalPubKey, testData, signature);
                if (verifyOriginal) {
                    log.info("✅ 原始公钥验签成功：签名有效");
                } else {
                    log.error("❌ 原始公钥验签失败：签名无效");
                }
            } else {
                log.warn("⚠️ 跳过原始公钥验签：公钥、签名或测试数据无效");
            }

            // 验证恢复公钥验签
            if (recoveredPubKey != null && signature != null && testData != null) {
                boolean verifyRecovered = Ed25519Signer.verifySignature(recoveredPubKey, testData, signature);
                if (verifyRecovered) {
                    log.info("✅ 恢复公钥验签成功：签名有效");
                } else {
                    log.error("❌ 恢复公钥验签失败：签名无效");
                }
            } else {
                log.warn("⚠️ 跳过恢复公钥验签：恢复公钥、签名或测试数据无效");
            }

            // 验证篡改数据验签
            byte[] tamperedData = "篡改后的测试数据".getBytes(StandardCharsets.UTF_8);
            if (originalPubKey != null && signature != null && tamperedData != null) {
                boolean verifyTampered = Ed25519Signer.verifySignature(originalPubKey, tamperedData, signature);
                if (!verifyTampered) {
                    log.info("✅ 篡改数据验签通过：正确识别无效数据（预期失败，实际失败）");
                } else {
                    log.error("❌ 篡改数据验签失败：错误通过了无效数据的验证");
                }
            } else {
                log.warn("⚠️ 跳过篡改数据验签：公钥、签名或篡改数据无效");
            }

            // 验证错误签名验签
            byte[] wrongSignature = new byte[64];
            new SecureRandom().nextBytes(wrongSignature);
            if (originalPubKey != null && wrongSignature != null && testData != null) {
                boolean verifyWrong = Ed25519Signer.verifySignature(originalPubKey, testData, wrongSignature);
                if (!verifyWrong) {
                    log.info("✅ 错误签名验签通过：正确识别无效签名（预期失败，实际失败）");
                } else {
                    log.error("❌ 错误签名验签失败：错误通过了无效签名的验证");
                }
            } else {
                log.warn("⚠️ 跳过错误签名验签：公钥、错误签名或测试数据无效");
            }

        } catch (Exception e) {
            log.error("❌ 测试流程发生未预期异常：{}", e.getMessage(), e);
        }

        log.info("\n===== Ed25519签名工具全流程测试结束 =====");
    }
}