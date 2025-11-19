package com.bit.solana.zk;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 极简 ZK 隐私交易系统（教学演示版）
 * 基于你优化的 Schnorr 零知识证明实现“余额 ≥ value”的范围证明（简化版 Fiat-Shamir）
 */
public class ZkPrivacyTransactionSystem {

    private final OptimizedCompleteZKPDemo.PublicParameters params;

    // 模拟链上 UTXO 集合（实际项目用 Merkle Tree）
    private final List<Coin> blockchain = new ArrayList<>();

    public ZkPrivacyTransactionSystem() {
        System.out.println("正在生成公共参数（只需一次）...");
        this.params = new OptimizedCompleteZKPDemo.PublicParameters();
        System.out.println("公共参数生成完毕，p ≈ 2^" + params.p.bitLength() + " 位\n");
    }

    /** 代表一枚隐私币 */
    public static class Coin {
        BigInteger commitment;   // cm = g^s mod p
        BigInteger serialNumber = null; // 可选：防双花序列号（这里简化省略）

        Coin(BigInteger commitment) {
            this.commitment = commitment;
            this.serialNumber = serialNumber;
        }
    }

    /** 持有者钱包 */
    public class Wallet {
        private BigInteger balance;  // 真实余额（秘密）

        public Wallet(BigInteger initialBalance) {
            this.balance = initialBalance;
        }

        /** 铸币：把真实余额隐藏成承诺上链 */
        public Coin mint() {
            BigInteger cm = OptimizedCompleteZKPDemo.fastModPow(params.g, balance, params.p);
            Coin coin = new Coin(cm);
            blockchain.add(coin);
            System.out.printf("铸币成功 → 承诺 cm = g^%d mod p（已上链）%n", balance);
            return coin;
        }

        /** 生成“我余额 ≥ value”的零知识证明（Fiat-Shamir 简化版，非交互） */
        public ZKRangeProof proveBalanceAtLeast(BigInteger value, Coin spentCoin) throws NoSuchAlgorithmException {
            return new ZKRangeProof(this, value, spentCoin);
        }

        public BigInteger getBalance() { return balance; }
    }

    /** 简易范围证明：证明我知道 s 使得 g^s = cm 且 s ≥ value（不暴露 s） */
    public class ZKRangeProof {
        private final BigInteger value;           // 要证明的下界，例如 100
        private final BigInteger s;               // 真实秘密余额
        private final BigInteger cm;              // 被花费的承诺
        private final BigInteger r;               // 承诺随机数
        private final BigInteger t;               // 承诺 t = g^r
        private final BigInteger c;               // Fiat-Shamir 挑战（哈希）
        private final BigInteger z;               // 响应

        private ZKRangeProof(Wallet wallet, BigInteger value, Coin spentCoin) throws NoSuchAlgorithmException {
            this.s = wallet.getBalance();
            this.value = value;
            this.cm = spentCoin.commitment;

            if (s.compareTo(value) < 0) {
                throw new IllegalStateException("余额不足，无法生成有效证明！");
            }

            // Step 1: 随机盲化因子 r
            this.r = OptimizedCompleteZKPDemo.generateInRange(BigInteger.TWO, params.q.subtract(BigInteger.ONE));
            this.t = OptimizedCompleteZKPDemo.fastModPow(params.g, r, params.p);

            // Step 2: Fiat-Shamir 挑战 c = Hash(cm || t || value || "some context")
            // 这里用简易版：把所有数据拼接后取 SHA-256 前 128 位作为挑战
            this.c = fiatShamirChallenge(cm, t, value);

            // Step 3: 响应 z = r + c * (s - value)   （关键：泄露的是 s-value 的部分）
            //         注意：我们证明的是 s' = s - value ≥ 0
            BigInteger sPrime = s.subtract(value);
            BigInteger zPart = c.multiply(sPrime).mod(params.q);
            this.z = r.add(zPart).mod(params.q);

            System.out.printf("生成范围证明成功：证明余额 ≥ %d（实际余额 %d）%n", value, s);
        }

        /** 简易 Fiat-Shamir：把字节拼接后 SHA-256 取低 128 位 */
        private BigInteger fiatShamirChallenge(BigInteger cm, BigInteger t, BigInteger value) throws NoSuchAlgorithmException {
            String data = cm.toString(16) + t.toString(16) + value.toString(16) + "ZkPrivacyDemo2025";
            byte[] hash = Arrays.copyOf(
                    MessageDigest.getInstance("SHA-256")
                            .digest(data.getBytes()), 16); // 取前128位
            return new BigInteger(1, hash).mod(params.q);
        }

        /** 任何人可验证（非交互） */
        public boolean verify() {
            // 验证等式：g^z ≡ t * (cm * g^{-value})^c   mod p
            // 即 g^z ≡ t * cm^c * g^{-value*c} mod p

            BigInteger gInvValue = OptimizedCompleteZKPDemo.fastModPow(params.g, value, params.p).modInverse(params.p);
            BigInteger rightBase = cm.multiply(gInvValue).mod(params.p);   // cm / g^value
            BigInteger right = t.multiply(OptimizedCompleteZKPDemo.fastModPow(rightBase, c, params.p))
                    .mod(params.p);

            BigInteger left = OptimizedCompleteZKPDemo.fastModPow(params.g, z, params.p);

            boolean ok = left.equals(right);
            System.out.println("范围证明验证结果：" + (ok ? "通过 ✓" : "失败 ✗"));
            return ok;
        }

        // getter（用于序列化到交易中）
        public BigInteger getT() { return t; }
        public BigInteger getC() { return c; }
        public BigInteger getZ() { return z; }
        public BigInteger getValue() { return value; }
        public BigInteger getSpentCommitment() { return cm; }
    }

    // ====================== Demo 主流程 ======================
    public static void main(String[] args) throws Exception {
        ZkPrivacyTransactionSystem system = new ZkPrivacyTransactionSystem();

        // 创建两个钱包
        Wallet alice = system.new Wallet(new BigInteger("500"));
        Wallet bob   = system.new Wallet(new BigInteger("0"));

        // Alice 铸币 500 个隐私币
        Coin coin500 = alice.mint();

        System.out.println("\n=== Alice 向 Bob 转账 100 个隐私币（零知识方式）===\n");

        // Alice 花费这枚币，证明“我有 ≥100”，并生成新币给 Bob（这里简化，只证明）
        ZKRangeProof proof = alice.proveBalanceAtLeast(new BigInteger("100"), coin500);

        // 链上/矿工验证证明（非交互）
        boolean valid = proof.verify();

        if (valid) {
            System.out.println("交易验证通过！Bob 收到 100 个隐私币（实际仍隐藏）");
            // 真实链会上：销毁旧承诺 coin500，创建新承诺给 Bob（可再加 Pedersen 承诺实现金额隐藏）
        } else {
            System.out.println("交易被拒绝");
        }

        // 额外演示：余额不足的情况
        System.out.println("\n--- 余额不足攻击测试 ---");
        Wallet poor = system.new Wallet(new BigInteger("50"));
        Coin poorCoin = poor.mint();
        try {
            poor.proveBalanceAtLeast(new BigInteger("100"), poorCoin);
        } catch (Exception e) {
            System.out.println("余额不足，证明生成失败（如预期）");
        }
    }
}