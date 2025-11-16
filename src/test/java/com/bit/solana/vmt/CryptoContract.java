package com.bit.solana.vmt;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Base64;

public class CryptoContract {
    // 核心存储结构
    private static final Map<String, Long> accountBalances = new ConcurrentHashMap<>();
    private static final Map<String, KeyPair> accountKeys = new ConcurrentHashMap<>();
    private static final List<Map<String, Object>> transactionHistory = new ArrayList<>();
    private static final Set<String> processedTransactions = new HashSet<>();

    // 系统参数
    private static final String CURVE_NAME = "secp256r1";
    private static final String SIGNATURE_ALGORITHM = "SHA256withECDSA";
    private static long transactionCounter = 0;
    private static final long MINING_REWARD = 100;
    private static final int DIFFICULTY = 4;

    // 创建账户
    public static String createAccount(String address) {
        if (address == null || address.trim().isEmpty()) {
            throw new IllegalArgumentException("地址不能为空");
        }
        if (accountKeys.containsKey(address)) {
            throw new IllegalStateException("账户已存在: " + address);
        }

        try {
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance("EC");
            ECGenParameterSpec ecSpec = new ECGenParameterSpec(CURVE_NAME);
            keyGen.initialize(ecSpec, new SecureRandom());
            KeyPair keyPair = keyGen.generateKeyPair();

            accountKeys.put(address, keyPair);
            accountBalances.putIfAbsent(address, 0L);
            return Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
        } catch (Exception e) {
            throw new RuntimeException("账户创建失败", e);
        }
    }

    // 获取余额
    public static long getBalance(String address) {
        return accountBalances.getOrDefault(address, 0L);
    }

    // 生成交易签名
    public static String signTransaction(String address, Map<String, Object> transaction) {
        if (!accountKeys.containsKey(address)) {
            throw new IllegalArgumentException("账户不存在: " + address);
        }

        try {
            String txData = serializeTransaction(transaction);
            PrivateKey privateKey = accountKeys.get(address).getPrivate();

            Signature signature = Signature.getInstance(SIGNATURE_ALGORITHM);
            signature.initSign(privateKey);
            signature.update(txData.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (Exception e) {
            throw new RuntimeException("签名生成失败", e);
        }
    }

    // 验证交易签名
    public static boolean verifyTransaction(String publicKeyStr, Map<String, Object> transaction, String signatureStr) {
        try {
            byte[] publicKeyBytes = Base64.getDecoder().decode(publicKeyStr);
            byte[] signatureBytes = Base64.getDecoder().decode(signatureStr);
            String txData = serializeTransaction(transaction);

            KeyFactory keyFactory = KeyFactory.getInstance("EC");
            PublicKey publicKey = keyFactory.generatePublic(new X509EncodedKeySpec(publicKeyBytes));

            Signature verifier = Signature.getInstance(SIGNATURE_ALGORITHM);
            verifier.initVerify(publicKey);
            verifier.update(txData.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(signatureBytes);
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    // 执行转账交易（修正：接收完整交易对象，避免数据重建）
    public static Map<String, Object> transfer(Map<String, Object> transaction, String signature) {
        // 从交易对象中提取必要字段
        String txId = (String) transaction.get("txId");
        String from = (String) transaction.get("from");
        String to = (String) transaction.get("to");
        long amount = (long) transaction.get("amount");

        // 复制交易对象，避免修改原始数据
        Map<String, Object> processedTx = new HashMap<>(transaction);
        processedTx.put("status", "PENDING");

        // 防止重复交易
        if (processedTransactions.contains(txId)) {
            processedTx.put("status", "FAILED");
            processedTx.put("message", "交易已处理（防双重支付）");
            transactionHistory.add(processedTx);
            return processedTx;
        }

        try {
            // 基础参数验证
            if (amount <= 0) {
                throw new IllegalArgumentException("转账金额必须为正数");
            }
            if (from.equals(to)) {
                throw new IllegalArgumentException("不能向自己转账");
            }
            if (!accountKeys.containsKey(from)) {
                throw new IllegalArgumentException("转出账户不存在");
            }
            if (!accountKeys.containsKey(to)) {
                throw new IllegalArgumentException("转入账户不存在");
            }

            // 签名验证（使用原始交易数据，确保时间戳等一致）
            String fromPublicKey = Base64.getEncoder().encodeToString(accountKeys.get(from).getPublic().getEncoded());
            if (!verifyTransaction(fromPublicKey, transaction, signature)) {
                throw new SecurityException("签名验证失败，交易无效");
            }

            // 余额校验与更新
            synchronized (accountBalances) {
                long fromBalance = getBalance(from);
                if (fromBalance < amount) {
                    throw new IllegalStateException("余额不足，当前余额: " + fromBalance + "，需转账: " + amount);
                }

                accountBalances.put(from, fromBalance - amount);
                accountBalances.put(to, getBalance(to) + amount);
            }

            processedTx.put("status", "SUCCESS");
            processedTransactions.add(txId);
        } catch (Exception e) {
            processedTx.put("status", "FAILED");
            processedTx.put("message", e.getMessage());
        } finally {
            transactionHistory.add(processedTx);
        }

        return processedTx;
    }

    // 挖矿功能
    public static Map<String, Object> mine(String minerAddress) {
        if (!accountKeys.containsKey(minerAddress)) {
            throw new IllegalArgumentException("矿工账户不存在，请先创建账户");
        }

        Map<String, Object> result = new HashMap<>();
        long startTime = System.currentTimeMillis();
        String nonce = mineBlock();

        synchronized (accountBalances) {
            accountBalances.put(minerAddress, getBalance(minerAddress) + MINING_REWARD);
        }

        Map<String, Object> rewardTx = new HashMap<>();
        rewardTx.put("txId", "REWARD-" + System.currentTimeMillis());
        rewardTx.put("from", "SYSTEM");
        rewardTx.put("to", minerAddress);
        rewardTx.put("amount", MINING_REWARD);
        rewardTx.put("timestamp", System.currentTimeMillis());
        rewardTx.put("status", "SUCCESS");
        rewardTx.put("nonce", nonce);
        transactionHistory.add(rewardTx);

        result.put("success", true);
        result.put("reward", MINING_REWARD);
        result.put("timeTaken", System.currentTimeMillis() - startTime);
        result.put("nonce", nonce);
        result.put("miner", minerAddress);

        return result;
    }

    // 获取账户历史
    public static List<Map<String, Object>> getAccountHistory(String address) {
        List<Map<String, Object>> history = new ArrayList<>();
        for (Map<String, Object> tx : transactionHistory) {
            if (address.equals(tx.get("from")) || address.equals(tx.get("to"))) {
                history.add(new HashMap<>(tx));
            }
        }
        return history;
    }

    // 获取所有余额
    public static Map<String, Long> getAllBalances() {
        return new HashMap<>(accountBalances);
    }

    // 生成交易ID
    public static String generateTxId(String from, String to, long amount) {
        String data = from + to + amount + System.currentTimeMillis() + (transactionCounter++);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("生成交易ID失败", e);
        }
    }

    // 序列化交易数据
    private static String serializeTransaction(Map<String, Object> transaction) {
        List<String> parts = new ArrayList<>();
        List<String> keys = new ArrayList<>(transaction.keySet());
        Collections.sort(keys);

        for (String key : keys) {
            if (!"status".equals(key) && !"message".equals(key)) {
                parts.add(key + "=" + transaction.get(key));
            }
        }
        return String.join("&", parts);
    }

    // 挖矿核心逻辑
    private static String mineBlock() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String prefix = String.join("", Collections.nCopies(DIFFICULTY, "0"));
            String blockData = String.valueOf(System.currentTimeMillis());
            long nonce = 0;

            while (true) {
                String input = blockData + nonce;
                byte[] hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
                String hashHex = bytesToHex(hashBytes);

                if (hashHex.startsWith(prefix)) {
                    System.out.println("挖矿成功！Nonce: " + nonce + "，哈希: " + hashHex);
                    return String.valueOf(nonce);
                }
                nonce++;
            }
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("挖矿失败", e);
        }
    }

    // 字节转十六进制
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    // 测试入口  javac -d . CryptoContract.java
    public static void main(String[] args) {
        System.out.println("=== 启动加密货币合约（基于secp256r1曲线）===");

        // 1. 创建测试账户
        String alice = "Alice";
        String bob = "Bob";
        try {
            String alicePubKey = createAccount(alice);
            String bobPubKey = createAccount(bob);
            System.out.println("\n=== 账户创建成功 ===");
            System.out.println(alice + " 公钥: " + alicePubKey);
            System.out.println(bob + " 公钥: " + bobPubKey);
        } catch (Exception e) {
            System.err.println("账户创建失败: " + e.getMessage());
            return;
        }

        // 2. Alice挖矿获取初始资金
        System.out.println("\n=== 开始挖矿（Alice作为矿工）===");
        Map<String, Object> mineResult = mine(alice);
        System.out.println("挖矿结果: " + mineResult.get("success"));
        System.out.println("奖励金额: " + mineResult.get("reward") + " 币");
        System.out.println("挖矿耗时: " + mineResult.get("timeTaken") + "ms");
        System.out.println(alice + " 余额: " + getBalance(alice));

        // 3. Alice向Bob转账（修正：使用完整交易对象确保数据一致）
        System.out.println("\n=== 执行转账（Alice -> Bob）===");
        long transferAmount = 30;
        // 生成唯一txId
        String txId = generateTxId(alice, bob, transferAmount);
        // 构建完整交易数据（包含所有必要字段，时间戳只设置一次）
        Map<String, Object> tx = new HashMap<>();
        tx.put("txId", txId);
        tx.put("from", alice);
        tx.put("to", bob);
        tx.put("amount", transferAmount);
        tx.put("timestamp", System.currentTimeMillis()); // 时间戳仅在此处设置

        // 签名交易（使用完整交易数据）
        String signature = signTransaction(alice, tx);
        System.out.println("交易签名: " + signature);

        // 执行转账（传入完整交易对象和签名）
        Map<String, Object> transferResult = transfer(tx, signature);
        System.out.println("转账状态: " + transferResult.get("status"));
        if (transferResult.get("message") != null) {
            System.out.println("转账说明: " + transferResult.get("message"));
        }

        // 4. 查看转账后余额
        System.out.println("\n=== 转账后余额 ===");
        System.out.println(alice + ": " + getBalance(alice) + " 币");
        System.out.println(bob + ": " + getBalance(bob) + " 币");

        // 5. 查看Alice的交易历史
        System.out.println("\n=== " + alice + " 的交易历史 ===");
        List<Map<String, Object>> aliceHistory = getAccountHistory(alice);
        for (Map<String, Object> t : aliceHistory) {
            System.out.printf(
                    "交易ID: %s | 方向: %s -> %s | 金额: %d | 状态: %s | 时间: %dms%n",
                    t.get("txId"),
                    t.get("from"),
                    t.get("to"),
                    t.get("amount"),
                    t.get("status"),
                    t.get("timestamp")
            );
        }

        // 6. 测试重复转账（防双重支付）
        System.out.println("\n=== 测试重复转账（防双重支付）===");
        Map<String, Object> duplicateTx = transfer(tx, signature);
        System.out.println("重复转账状态: " + duplicateTx.get("status"));
        System.out.println("重复转账说明: " + duplicateTx.get("message"));
    }
}