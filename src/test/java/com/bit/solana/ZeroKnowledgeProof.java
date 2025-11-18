package com.bit.solana;

import java.math.BigInteger;
import java.security.SecureRandom;

public class ZeroKnowledgeProof {
    public static void main(String[] args) {
        // 1. 生成公共参数（p：大素数，g：生成元）
        SecureRandom random = new SecureRandom();
        int bitLength = 512; // 素数位数（实际应用需2048位以上）
        BigInteger p = BigInteger.probablePrime(bitLength, random); // 随机大素数p
        BigInteger g = BigInteger.valueOf(2); // 生成元（简化处理，实际需验证）

        // 2. 证明者生成秘密及公开值
        BigInteger s = new BigInteger(bitLength - 1, random); // 秘密值s（1 < s < p-1）
        BigInteger v = g.modPow(s, p); // 公开值v = g^s mod p

        // 3. 协议交互
        // 3.1 证明者生成承诺t
        BigInteger r = new BigInteger(bitLength - 1, random); // 随机数r
        BigInteger t = g.modPow(r, p); // 承诺t = g^r mod p

        // 3.2 验证者生成挑战c
        BigInteger c = new BigInteger(32, random); // 32位随机挑战（范围可调整）

        // 3.3 证明者生成响应z
        BigInteger z = r.add(c.multiply(s)).mod(p.subtract(BigInteger.ONE)); // z = (r + c*s) mod (p-1)

        // 3.4 验证者验证
        BigInteger left = g.modPow(z, p); // 左侧：g^z mod p
        BigInteger right = t.multiply(v.modPow(c, p)).mod(p); // 右侧：(t * v^c) mod p
        boolean isVerified = left.equals(right);

        // 输出结果
        System.out.println("公共参数：p=" + p + "\ng=" + g);
        System.out.println("证明者秘密s（不泄露）：" + s);
        System.out.println("公开值v = g^s mod p：" + v);
        System.out.println("验证结果：" + (isVerified ? "通过（证明者知道秘密）" : "失败"));

        // 测试：证明者不知道秘密时的验证（预期失败）
        testFakeProver(p, g, v, c, t, random);
    }

    // 模拟“不知道秘密”的证明者（随机生成z）
    private static void testFakeProver(BigInteger p, BigInteger g, BigInteger v, BigInteger c, BigInteger t, SecureRandom random) {
        BigInteger fakeZ = new BigInteger(p.bitLength() - 1, random); // 随机z（不知道s）
        BigInteger left = g.modPow(fakeZ, p);
        BigInteger right = t.multiply(v.modPow(c, p)).mod(p);
        boolean isFakeVerified = left.equals(right);
        System.out.println("伪造证明验证结果：" + (isFakeVerified ? "错误通过" : "正确失败"));
    }
}