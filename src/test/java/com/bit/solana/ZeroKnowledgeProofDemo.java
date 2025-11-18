package com.bit.solana;

import java.math.BigInteger;
import java.security.SecureRandom;

/**
 * 零知识证明基础示例
 * 场景：证明者知道某个秘密值x，使得 g^x ≡ h (mod p)，但不泄露x
 */
public class ZeroKnowledgeProofDemo {
    // 大素数p（模）
    private static final BigInteger p = new BigInteger("115792089237316195423570985008687907853269984665640564039457584007913129639747");
    // p的原根g（生成元）
    private static final BigInteger g = BigInteger.valueOf(2);
    private static final SecureRandom random = new SecureRandom();

    static class Prover {
        private final BigInteger secretX;  // 证明者的秘密
        private final BigInteger h;        // 公开值 h = g^x mod p

        public Prover(BigInteger secretX) {
            this.secretX = secretX;
            this.h = g.modPow(secretX, p);
        }

        /**
         * 生成承诺（commitment）
         * 随机选择一个值r，计算 t = g^r mod p
         */
        public BigInteger generateCommitment() {
            // 生成一个随机数r（范围：1到p-2）
            BigInteger r;
            do {
                r = new BigInteger(p.bitLength() - 1, random);
            } while (r.compareTo(BigInteger.ZERO) <= 0 || r.compareTo(p.subtract(BigInteger.TWO)) >= 0);

            return g.modPow(r, p);
        }

        /**
         * 生成响应（response）
         * 根据验证者的挑战c，计算 s = (r + c*x) mod (p-1)
         * 注：这里使用费马小定理的推论，指数模为p-1
         */
        public BigInteger generateResponse(BigInteger r, BigInteger challenge) {
            BigInteger exponentMod = p.subtract(BigInteger.ONE);  // p-1
            return r.add(challenge.multiply(secretX)).mod(exponentMod);
        }

        public BigInteger getH() {
            return h;
        }
    }

    static class Verifier {
        /**
         * 生成挑战（challenge）
         * 随机选择一个值c（0或1，简化版本）
         */
        public BigInteger generateChallenge() {
            return BigInteger.valueOf(random.nextInt(2));  // 简化版：0或1
        }

        /**
         * 验证响应
         * 检查 g^s ≡ (t * h^c) mod p 是否成立
         */
        public boolean verify(BigInteger commitment, BigInteger challenge,
                              BigInteger response, BigInteger h) {
            BigInteger left = g.modPow(response, p);
            BigInteger right = commitment.multiply(h.modPow(challenge, p)).mod(p);
            return left.equals(right);
        }
    }

    public static void main(String[] args) {
        // 1. 初始化：证明者有一个秘密x
        BigInteger secretX = new BigInteger("123456789");  // 秘密值
        Prover prover = new Prover(secretX);
        Verifier verifier = new Verifier();

        System.out.println("零知识证明演示开始");
        System.out.println("公开参数: p=" + p + ", g=" + g);
        System.out.println("公开值h = g^x mod p: " + prover.getH());
        System.out.println("(秘密x不会被泄露)");

        // 2. 执行零知识证明协议
        boolean proofValid = true;
        int rounds = 5;  // 多轮验证提高可信度

        for (int i = 0; i < rounds; i++) {
            System.out.println("\n第" + (i+1) + "轮验证:");

            // 步骤1：证明者生成承诺
            BigInteger r = new BigInteger(p.bitLength() - 1, random);  // 实际中应在Prover内部生成
            BigInteger commitment = prover.generateCommitment();
            System.out.println("承诺t = g^r mod p: " + commitment);

            // 步骤2：验证者生成挑战
            BigInteger challenge = verifier.generateChallenge();
            System.out.println("挑战c: " + challenge);

            // 步骤3：证明者生成响应
            BigInteger response = prover.generateResponse(r, challenge);
            System.out.println("响应s: " + response);

            // 步骤4：验证者验证响应
            boolean valid = verifier.verify(commitment, challenge, response, prover.getH());
            System.out.println("本轮验证结果: " + (valid ? "有效" : "无效"));

            if (!valid) {
                proofValid = false;
                break;
            }
        }

        System.out.println("\n最终验证结果: " + (proofValid ? "证明有效 - 证明者确实知道秘密x" : "证明无效 - 证明者不知道秘密x"));
    }
}