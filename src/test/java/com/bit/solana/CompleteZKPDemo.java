package com.bit.solana;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * 零知识证明演示：证明者在不泄露秘密的情况下，向验证者证明自己知道某个秘密
 * 基于离散对数问题（Discrete Logarithm Problem）构建
 */
public class CompleteZKPDemo {
    // 安全参数：素数位数（实际应用中建议2048位以上）
    private static final int PRIME_BIT_LENGTH = 512;
    // 挑战值位数（影响安全性，通常128位以上）
    private static final int CHALLENGE_BIT_LENGTH = 128;
    // 随机数生成器（密码学安全）
    private static final SecureRandom secureRandom = new SecureRandom();

    /**
     * 公共参数生成器：生成p（大素数）和g（生成元）
     * 满足p = 2q + 1（q为素数），确保g的阶为q（增强安全性）
     */
    public static class PublicParameters {
        public BigInteger p; // 大素数 modulus
        public BigInteger q; // p-1的大素数因子（确保循环群阶为素数）
        public final BigInteger g; // 生成元（循环群的生成元）

        public PublicParameters() {
            // 生成安全素数p = 2q + 1（q为素数）
            do {
                q = BigInteger.probablePrime(PRIME_BIT_LENGTH - 1, secureRandom);
                p = q.shiftLeft(1).add(BigInteger.ONE); // p = 2q + 1
            } while (!p.isProbablePrime(100)); // 验证p是否为素数（100次Miller-Rabin测试）

            // 寻找生成元g（满足g^q ≡ 1 mod p，且g≠1）
            BigInteger gCandidate;
            do {
                // 在[2, p-2]范围内随机选择候选生成元
                gCandidate = new BigInteger(p.bitLength() - 1, secureRandom);
                if (gCandidate.compareTo(BigInteger.ONE) <= 0) {
                    gCandidate = BigInteger.TWO;
                }
            } while (gCandidate.modPow(q, p).equals(BigInteger.ONE) == false);
            this.g = gCandidate;
        }
    }

    /**
     * 证明者（Prover）：知道秘密值s，需向验证者证明自己知道s但不泄露s
     */
    public static class Prover {
        private final PublicParameters pubParams;
        private BigInteger secretS; // 秘密值（核心隐私数据）
        private final BigInteger publicV; // 公开值v = g^s mod p（可公开给验证者）
        private BigInteger commitmentR; // 承诺阶段使用的随机数r（临时变量）

        public Prover(PublicParameters pubParams) {
            this.pubParams = pubParams;
            // 生成秘密值s（范围：1 < s < q，确保在循环群内）
            do {
                this.secretS = new BigInteger(pubParams.q.bitLength() - 1, secureRandom);
            } while (this.secretS.compareTo(BigInteger.ONE) <= 0 || this.secretS.compareTo(pubParams.q) >= 0);

            // 计算公开值v = g^s mod p（公开参数，验证者可获取）
            this.publicV = pubParams.g.modPow(secretS, pubParams.p);
        }

        /**
         * 第一步：生成承诺（Commitment）
         * 证明者选择随机数r，计算t = g^r mod p并发送给验证者
         */
        public BigInteger generateCommitment() {
            // 生成随机数r（范围：1 < r < q）
            do {
                commitmentR = new BigInteger(pubParams.q.bitLength() - 1, secureRandom);
            } while (commitmentR.compareTo(BigInteger.ONE) <= 0 || commitmentR.compareTo(pubParams.q) >= 0);

            // 计算承诺t = g^r mod p
            return pubParams.g.modPow(commitmentR, pubParams.p);
        }

        /**
         * 第三步：生成响应（Response）
         * 根据验证者的挑战c，计算z = (r + c*s) mod q并发送给验证者
         */
        public BigInteger generateResponse(BigInteger challengeC) {
            // 确保挑战c在有效范围（1 < c < q）
            if (challengeC.compareTo(BigInteger.ONE) <= 0 || challengeC.compareTo(pubParams.q) >= 0) {
                throw new IllegalArgumentException("无效的挑战值，必须在(1, q)范围内");
            }
            // 计算z = (r + c*s) mod q
            BigInteger cMultiplyS = challengeC.multiply(secretS).mod(pubParams.q);
            return commitmentR.add(cMultiplyS).mod(pubParams.q);
        }

        // 获取公开值（供验证者使用）
        public BigInteger getPublicV() {
            return publicV;
        }

        // 仅用于演示：获取秘密值（实际中绝对不泄露）
        public BigInteger getSecretS() {
            return secretS;
        }
    }

    /**
     * 验证者（Verifier）：验证证明者是否真的知道秘密s
     */
    public static class Verifier {
        private final PublicParameters pubParams;
        private BigInteger receivedCommitmentT; // 收到的承诺t
        private BigInteger generatedChallengeC; // 生成的挑战c

        public Verifier(PublicParameters pubParams) {
            this.pubParams = pubParams;
        }

        /**
         * 第二步：生成挑战（Challenge）
         * 收到证明者的承诺t后，生成随机挑战c并发送给证明者
         */
        public BigInteger generateChallenge(BigInteger commitmentT) {
            this.receivedCommitmentT = commitmentT;
            // 生成挑战c（范围：1 < c < q）
            do {
                generatedChallengeC = new BigInteger(CHALLENGE_BIT_LENGTH, secureRandom);
            } while (generatedChallengeC.compareTo(BigInteger.ONE) <= 0 || generatedChallengeC.compareTo(pubParams.q) >= 0);
            return generatedChallengeC;
        }

        /**
         * 第四步：验证响应（Verification）
         * 收到证明者的响应z后，验证g^z ≡ (t * v^c) mod p是否成立
         */
        public boolean verifyResponse(BigInteger responseZ, BigInteger proverPublicV) {
            // 计算左侧：g^z mod p
            BigInteger left = pubParams.g.modPow(responseZ, pubParams.p);

            // 计算右侧：(t * v^c) mod p
            BigInteger vPowC = proverPublicV.modPow(generatedChallengeC, pubParams.p);
            BigInteger right = receivedCommitmentT.multiply(vPowC).mod(pubParams.p);

            // 验证等式是否成立
            return left.equals(right);
        }
    }

    /**
     * 模拟恶意证明者（不知道秘密s）尝试伪造证明
     */
    public static class MaliciousProver {
        private final PublicParameters pubParams;
        private final BigInteger publicV; // 知道公开值v，但不知道对应的s

        public MaliciousProver(PublicParameters pubParams, BigInteger publicV) {
            this.pubParams = pubParams;
            this.publicV = publicV;
        }

        // 恶意策略1：先猜挑战c，再构造t（可能被验证者识破）
        public BigInteger[] fakeCommitmentAndResponse() {
            // 随机生成z和c'（伪造的挑战）
            BigInteger fakeZ = new BigInteger(pubParams.q.bitLength() - 1, secureRandom);
            BigInteger fakeC = new BigInteger(CHALLENGE_BIT_LENGTH, secureRandom);

            // 反向计算t' = g^z / v^c' mod p（试图匹配伪造的z和c'）
            BigInteger vPowFakeC = publicV.modPow(fakeC, pubParams.p);
            BigInteger tInverse = vPowFakeC.modInverse(pubParams.p); // 计算v^c'的逆元
            BigInteger fakeT = pubParams.g.modPow(fakeZ, pubParams.p).multiply(tInverse).mod(pubParams.p);

            return new BigInteger[]{fakeT, fakeC, fakeZ};
        }

        // 恶意策略2：收到挑战c后随机生成z（纯猜测）
        public BigInteger randomResponse() {
            return new BigInteger(pubParams.q.bitLength() - 1, secureRandom);
        }
    }

    public static void main(String[] args) {
        System.out.println("=== 零知识证明完整流程演示 ===");

        // 1. 生成公共参数（p, q, g）
        System.out.println("\n1. 生成公共参数（p: 大素数, q: p-1的素因子, g: 生成元）...");
        PublicParameters pubParams = new PublicParameters();
        System.out.println("   p的位数: " + pubParams.p.bitLength() + " bits");
        System.out.println("   q的位数: " + pubParams.q.bitLength() + " bits");
        System.out.println("   生成元g验证: g^q mod p = " + pubParams.g.modPow(pubParams.q, pubParams.p) + " (应等于1)");

        // 2. 初始化证明者和验证者
        System.out.println("\n2. 初始化证明者（知道秘密s）和验证者...");
        Prover prover = new Prover(pubParams);
        Verifier verifier = new Verifier(pubParams);
        System.out.println("   证明者生成秘密s（不泄露），公开值v = g^s mod p");

        // 3. 零知识证明交互流程
        System.out.println("\n3. 开始零知识证明交互...");

        // 3.1 证明者生成承诺t并发送给验证者
        System.out.println("   3.1 证明者生成承诺t = g^r mod p");
        BigInteger commitmentT = prover.generateCommitment();

        // 3.2 验证者生成挑战c并发送给证明者
        System.out.println("   3.2 验证者生成挑战c");
        BigInteger challengeC = verifier.generateChallenge(commitmentT);

        // 3.3 证明者生成响应z并发送给验证者
        System.out.println("   3.3 证明者生成响应z = (r + c*s) mod q");
        BigInteger responseZ = prover.generateResponse(challengeC);

        // 3.4 验证者验证响应
        System.out.println("   3.4 验证者验证：g^z ≡ (t * v^c) mod p ?");
        boolean isVerified = verifier.verifyResponse(responseZ, prover.getPublicV());
        System.out.println("   验证结果：" + (isVerified ? "通过（证明者确实知道秘密）" : "失败（证明者不知道秘密）"));

        // 4. 测试恶意证明者（不知道秘密s）
        System.out.println("\n4. 恶意证明者测试（不知道秘密s）...");
        MaliciousProver maliciousProver = new MaliciousProver(pubParams, prover.getPublicV());

        // 4.1 测试恶意策略1：先猜挑战再构造承诺
        System.out.println("   4.1 恶意策略（先猜挑战）：");
        BigInteger[] fakeData = maliciousProver.fakeCommitmentAndResponse();
        BigInteger fakeT = fakeData[0];
        BigInteger fakeC = fakeData[1];
        BigInteger fakeZ = fakeData[2];
        // 验证者使用真实挑战而非伪造的挑战
        Verifier verifier1 = new Verifier(pubParams);
        BigInteger realC = verifier1.generateChallenge(fakeT); // 生成真实挑战
        boolean fakeVerify1 = verifier1.verifyResponse(fakeZ, prover.getPublicV());
        System.out.println("   伪造验证结果：" + (fakeVerify1 ? "错误通过（极低概率）" : "正确失败"));

        // 4.2 测试恶意策略2：收到挑战后随机生成z
        System.out.println("   4.2 恶意策略（随机响应）：");
        Verifier verifier2 = new Verifier(pubParams);
        BigInteger t2 = prover.generateCommitment(); // 使用真实承诺（恶意者也可生成自己的t）
        BigInteger c2 = verifier2.generateChallenge(t2);
        BigInteger fakeZ2 = maliciousProver.randomResponse();
        boolean fakeVerify2 = verifier2.verifyResponse(fakeZ2, prover.getPublicV());
        System.out.println("   伪造验证结果：" + (fakeVerify2 ? "错误通过（极低概率）" : "正确失败"));

        // 5. 安全性说明
        System.out.println("\n=== 安全性说明 ===");
        System.out.println("1. 完备性：若证明者真的知道秘密，验证一定通过");
        System.out.println("2. 合理性：恶意证明者通过验证的概率极低（约1/(2^" + CHALLENGE_BIT_LENGTH + ")）");
        System.out.println("3. 零知识性：验证者无法从交互中获取任何关于秘密s的信息");
    }
}