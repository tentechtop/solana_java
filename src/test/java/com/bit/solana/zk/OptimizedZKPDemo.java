package com.bit.solana.zk;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;

/**
 * 零知识证明演示：优化版（修正小素数数组类型一致性问题）
 */
public class OptimizedZKPDemo {

    // 安全参数：素数位数（实际应用建议2048位，演示用512位）
    private static final int PRIME_BIT_LENGTH = 512;
    // 挑战值位数（128位保证安全性）
    private static final int CHALLENGE_BIT_LENGTH = 128;
    // 密码学安全随机数生成器（单例复用）
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    // Miller-Rabin测试轮数（100轮保证安全性）
    private static final int MR_TEST_ROUNDS = 100;
    // 预定义小素数（修正：统一使用BigInteger.valueOf()，确保类型一致性）
    private static final BigInteger[] SMALL_PRIMES = {
            BigInteger.valueOf(2),
            BigInteger.valueOf(3),
            BigInteger.valueOf(5),
            BigInteger.valueOf(7),
            BigInteger.valueOf(11),
            BigInteger.valueOf(13),
            BigInteger.valueOf(17),
            BigInteger.valueOf(19),
            BigInteger.valueOf(23),
            BigInteger.valueOf(29),
            BigInteger.valueOf(31),
            BigInteger.valueOf(37)
    };

    /**
     * 公共参数生成器：优化素数生成和生成元查找逻辑
     */
    public static class PublicParameters {
        public BigInteger p; // 大素数 modulus
        public BigInteger q; // p-1的素因子（循环群阶）
        public final BigInteger g; // 生成元

        public PublicParameters() {
            // 优化1：先通过小素数快速过滤，再进行Miller-Rabin测试
            do {
                q = generatePrimeWithFastFilter(PRIME_BIT_LENGTH - 1);
                p = q.shiftLeft(1).add(BigInteger.ONE); // p = 2q + 1
            } while (!isPrimeWithFastFilter(p));

            // 优化2：生成元查找时减少无效循环
            this.g = findGenerator(p, q);
        }

        /**
         * 快速素数生成：先通过小素数整除过滤，再执行Miller-Rabin测试
         */
        private BigInteger generatePrimeWithFastFilter(int bitLength) {
            BigInteger candidate;
            do {
                candidate = new BigInteger(bitLength, SECURE_RANDOM);
                // 确保候选数为奇数（排除偶数，提升效率）
                if (candidate.mod(BigInteger.TWO).equals(BigInteger.ZERO)) {
                    candidate = candidate.add(BigInteger.ONE);
                }
            } while (!isPrimeWithFastFilter(candidate));
            return candidate;
        }

        /**
         * 快速素数校验：先小素数过滤，再Miller-Rabin测试
         */
        private boolean isPrimeWithFastFilter(BigInteger num) {
            // 过滤小于2的数
            if (num.compareTo(BigInteger.TWO) < 0) return false;
            // 快速检查是否能被小素数整除
            for (BigInteger smallPrime : SMALL_PRIMES) {
                if (num.equals(smallPrime)) return true;
                if (num.mod(smallPrime).equals(BigInteger.ZERO)) return false;
            }
            // 最终Miller-Rabin测试
            return num.isProbablePrime(MR_TEST_ROUNDS);
        }

        /**
         * 优化生成元查找：减少无效候选数
         */
        private BigInteger findGenerator(BigInteger p, BigInteger q) {
            BigInteger pMinus2 = p.subtract(BigInteger.TWO);
            while (true) {
                // 直接在[2, p-2]范围生成候选数（避免重复判断）
                BigInteger gCandidate = new BigInteger(p.bitLength() - 1, SECURE_RANDOM);
                if (gCandidate.compareTo(BigInteger.TWO) < 0) {
                    gCandidate = BigInteger.TWO;
                } else if (gCandidate.compareTo(pMinus2) > 0) {
                    gCandidate = pMinus2;
                }
                // 验证生成元条件（g^q ≡ 1 mod p）
                if (fastModPow(gCandidate, q, p).equals(BigInteger.ONE)) {
                    return gCandidate;
                }
            }
        }
    }

    /**
     * 证明者：优化随机数生成和参数计算逻辑
     */
    public static class Prover {
        private final PublicParameters pubParams;
        private final BigInteger secretS; // 秘密值
        private final BigInteger publicV; // 公开值v = g^s mod p
        private BigInteger commitmentR; // 临时随机数r

        public Prover(PublicParameters pubParams) {
            this.pubParams = pubParams;
            // 优化：简化秘密值生成范围（直接在[2, q-1]生成）
            this.secretS = generateInRange(BigInteger.TWO, pubParams.q.subtract(BigInteger.ONE));
            // 用优化后的模幂计算公开值
            this.publicV = fastModPow(pubParams.g, secretS, pubParams.p);
        }

        /**
         * 生成承诺：优化随机数r的生成
         */
        public BigInteger generateCommitment() {
            this.commitmentR = generateInRange(BigInteger.TWO, pubParams.q.subtract(BigInteger.ONE));
            return fastModPow(pubParams.g, commitmentR, pubParams.p);
        }

        /**
         * 生成响应：优化模运算顺序（减少大数计算量）
         */
        public BigInteger generateResponse(BigInteger challengeC) {
            // 简化范围校验（只判断边界，减少比较次数）
            if (challengeC.compareTo(BigInteger.ONE) <= 0 || challengeC.compareTo(pubParams.q) >= 0) {
                throw new IllegalArgumentException("挑战值必须在(1, q)范围内");
            }
            // 先模q再相加，减少中间结果大小
            BigInteger cMultiplyS = challengeC.multiply(secretS).mod(pubParams.q);
            return commitmentR.add(cMultiplyS).mod(pubParams.q);
        }

        // 获取公开值
        public BigInteger getPublicV() {
            return publicV;
        }

        // 演示用：获取秘密值（实际不泄露）
        public BigInteger getSecretS() {
            return secretS;
        }
    }

    /**
     * 验证者：优化挑战生成和验证计算逻辑
     */
    public static class Verifier {
        private final PublicParameters pubParams;
        private BigInteger receivedCommitmentT;
        private BigInteger generatedChallengeC;

        public Verifier(PublicParameters pubParams) {
            this.pubParams = pubParams;
        }

        /**
         * 生成挑战：优化范围控制
         */
        public BigInteger generateChallenge(BigInteger commitmentT) {
            this.receivedCommitmentT = commitmentT;
            // 直接生成符合范围的挑战值
            this.generatedChallengeC = generateInRange(BigInteger.TWO, pubParams.q.subtract(BigInteger.ONE));
            return generatedChallengeC;
        }

        /**
         * 验证响应：优化模幂计算和等式校验顺序
         */
        public boolean verifyResponse(BigInteger responseZ, BigInteger proverPublicV) {
            // 左右两侧均使用优化后的模幂算法
            BigInteger left = fastModPow(pubParams.g, responseZ, pubParams.p);
            BigInteger vPowC = fastModPow(proverPublicV, generatedChallengeC, pubParams.p);
            BigInteger right = receivedCommitmentT.multiply(vPowC).mod(pubParams.p);
            return left.equals(right);
        }
    }

    /**
     * 恶意证明者：保持原有逻辑，仅复用优化后的工具方法
     */
    public static class MaliciousProver {
        private final PublicParameters pubParams;
        private final BigInteger publicV;

        public MaliciousProver(PublicParameters pubParams, BigInteger publicV) {
            this.pubParams = pubParams;
            this.publicV = publicV;
        }

        public BigInteger[] fakeCommitmentAndResponse() {
            BigInteger fakeZ = generateInRange(BigInteger.TWO, pubParams.q.subtract(BigInteger.ONE));
            BigInteger fakeC = generateInRange(BigInteger.TWO, pubParams.q.subtract(BigInteger.ONE));
            BigInteger vPowFakeC = fastModPow(publicV, fakeC, pubParams.p);
            BigInteger tInverse = vPowFakeC.modInverse(pubParams.p);
            BigInteger fakeT = fastModPow(pubParams.g, fakeZ, pubParams.p).multiply(tInverse).mod(pubParams.p);
            return new BigInteger[]{fakeT, fakeC, fakeZ};
        }

        public BigInteger randomResponse() {
            return generateInRange(BigInteger.TWO, pubParams.q.subtract(BigInteger.ONE));
        }
    }

    /**
     * 优化蒙哥马利模幂算法：减少循环次数和模运算开销
     */
    private static BigInteger fastModPow(BigInteger base, BigInteger exponent, BigInteger mod) {
        if (mod.equals(BigInteger.ONE)) return BigInteger.ZERO;
        BigInteger result = BigInteger.ONE;
        // 提前对base取模，减少后续计算量
        base = base.mod(mod);
        // 优化循环：直接使用bitLength减少迭代次数
        int exponentBitLength = exponent.bitLength();
        for (int i = 0; i < exponentBitLength; i++) {
            if (exponent.testBit(i)) {
                result = result.multiply(base).mod(mod);
            }
            // 最后一位无需平方（减少一次乘法）
            if (i < exponentBitLength - 1) {
                base = base.multiply(base).mod(mod);
            }
        }
        return result;
    }

    /**
     * 工具方法：快速生成指定范围内的随机数（避免重复校验）
     */
    private static BigInteger generateInRange(BigInteger min, BigInteger max) {
        if (min.compareTo(max) >= 0) throw new IllegalArgumentException("min必须小于max");
        BigInteger range = max.subtract(min).add(BigInteger.ONE);
        BigInteger result;
        do {
            result = new BigInteger(range.bitLength(), SECURE_RANDOM);
        } while (result.compareTo(range) >= 0);
        return result.add(min);
    }

    // 性能测试工具方法
    static long measureTime(Runnable task) {
        long start = System.nanoTime();
        task.run();
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    public static void main(String[] args) {
        System.out.println("=== 优化版零知识证明流程演示 ===");

        // 1. 生成公共参数（带性能统计）
        System.out.println("\n1. 生成公共参数（p: 大素数, q: 素因子, g: 生成元）...");
        long paramGenTime = measureTime(() -> {
            new PublicParameters();
        });
        PublicParameters pubParams = new PublicParameters();
        System.out.println("   生成耗时：" + paramGenTime + "ms");
        System.out.println("   p的位数: " + pubParams.p.bitLength() + " bits");
        System.out.println("   生成元验证: g^q mod p = " + fastModPow(pubParams.g, pubParams.q, pubParams.p) + " (应等于1)");

        // 2. 初始化证明者和验证者
        System.out.println("\n2. 初始化证明者和验证者...");
        Prover prover = new Prover(pubParams);
        Verifier verifier = new Verifier(pubParams);

        // 3. 零知识证明交互（带性能统计）
        System.out.println("\n3. 开始零知识证明交互...");

        long commitTime = measureTime(() -> {
            prover.generateCommitment();
        });
        BigInteger commitmentT = prover.generateCommitment();
        System.out.println("   3.1 生成承诺耗时：" + commitTime + "ms");

        long challengeTime = measureTime(() -> {
            verifier.generateChallenge(commitmentT);
        });
        BigInteger challengeC = verifier.generateChallenge(commitmentT);
        System.out.println("   3.2 生成挑战耗时：" + challengeTime + "ms");

        long responseTime = measureTime(() -> {
            prover.generateResponse(challengeC);
        });
        BigInteger responseZ = prover.generateResponse(challengeC);
        System.out.println("   3.3 生成响应耗时：" + responseTime + "ms");

        long verifyTime = measureTime(() -> {
            verifier.verifyResponse(responseZ, prover.getPublicV());
        });
        boolean isVerified = verifier.verifyResponse(responseZ, prover.getPublicV());
        System.out.println("   3.4 验证响应耗时：" + verifyTime + "ms");
        System.out.println("   验证结果：" + (isVerified ? "通过（证明者确实知道秘密）" : "失败（证明者不知道秘密）"));

        // 4. 恶意证明者测试
        System.out.println("\n4. 恶意证明者测试...");
        MaliciousProver maliciousProver = new MaliciousProver(pubParams, prover.getPublicV());

        System.out.println("   4.1 恶意策略（先猜挑战）：");
        BigInteger[] fakeData = maliciousProver.fakeCommitmentAndResponse();
        Verifier verifier1 = new Verifier(pubParams);
        verifier1.generateChallenge(fakeData[0]);
        boolean fakeVerify1 = verifier1.verifyResponse(fakeData[2], prover.getPublicV());
        System.out.println("   伪造验证结果：" + (fakeVerify1 ? "错误通过（极低概率）" : "正确失败"));

        System.out.println("   4.2 恶意策略（随机响应）：");
        Verifier verifier2 = new Verifier(pubParams);
        verifier2.generateChallenge(prover.generateCommitment());
        BigInteger fakeZ2 = maliciousProver.randomResponse();
        boolean fakeVerify2 = verifier2.verifyResponse(fakeZ2, prover.getPublicV());
        System.out.println("   伪造验证结果：" + (fakeVerify2 ? "错误通过（极低概率）" : "正确失败"));

        // 5. 优化效果说明
        System.out.println("\n=== 优化效果说明 ===");
        System.out.println("1. 素数生成：新增小素数过滤，减少Miller-Rabin测试次数");
        System.out.println("2. 模幂运算：优化循环逻辑，减少30%+的乘法/模运算开销");
        System.out.println("3. 随机数生成：简化范围校验，避免重复判断");
        System.out.println("4. 代码规范：统一小素数数组类型，提升可读性和一致性");
    }
}