package com.bit.solana.vmt;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Base64;

public class TokenStakingContract {
    // 核心存储结构
    private static final Map<String, KeyPair> accountKeys = new ConcurrentHashMap<>();
    private static final Map<String, Long> tokenBalances = new ConcurrentHashMap<>(); // 自由代币余额
    private static final List<Map<String, Object>> stakes = new ArrayList<>(); // 质押记录
    private static final List<Map<String, Object>> transactions = new ArrayList<>(); // 交易记录
    private static final Set<String> processedTxIds = new HashSet<>();
    private static final Map<String, Object> stakingConfig = new ConcurrentHashMap<>(); // 质押配置

    // 系统参数
    private static final String CURVE_NAME = "secp256r1";
    private static final String SIGNATURE_ALGORITHM = "SHA256withECDSA";
    private static final long TOTAL_TOKEN_SUPPLY = 100000000L; // 1亿代币
    private static final String TOKEN_NAME = "StakableToken";
    private static final String TOKEN_SYMBOL = "STK";
    private static long transactionCounter = 0;
    private static final String SYSTEM_ADMIN = "StakingAdmin"; // 系统管理员

    // 质押参数（年化收益率20%，按秒计算）
    private static final double ANNUAL_REWARD_RATE = 0.20;
    private static final long SECONDS_PER_YEAR = 31536000L; // 365天*24h*3600s
    private static final long MIN_STAKE_DURATION = 86400L; // 最小质押时长（24小时）

    // 初始化配置
    static {
        stakingConfig.put("tokenName", TOKEN_NAME);
        stakingConfig.put("tokenSymbol", TOKEN_SYMBOL);
        stakingConfig.put("totalSupply", TOTAL_TOKEN_SUPPLY);
        stakingConfig.put("annualRewardRate", ANNUAL_REWARD_RATE);
        stakingConfig.put("minStakeDuration", MIN_STAKE_DURATION);
        stakingConfig.put("status", "ACTIVE");
        stakingConfig.put("launchTime", System.currentTimeMillis() / 1000); // 启动时间（秒级）
    }

    // 1. 创建账户
    public static String createAccount(String address, String role) {
        if (address == null || address.trim().isEmpty() || role == null) {
            throw new IllegalArgumentException("地址和角色不能为空");
        }
        if (!Arrays.asList("ADMIN", "USER").contains(role)) {
            throw new IllegalArgumentException("无效角色：仅支持ADMIN/USER");
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
            tokenBalances.putIfAbsent(address, 0L);

            // 管理员初始持有全部代币
            if ("ADMIN".equals(role) && SYSTEM_ADMIN.equals(address)) {
                tokenBalances.put(address, TOTAL_TOKEN_SUPPLY);
            }

            return Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
        } catch (Exception e) {
            throw new RuntimeException("账户创建失败", e);
        }
    }

    // 2. 质押代币（核心功能）
    public static Map<String, Object> stakeTokens(Map<String, Object> transaction, String signature) {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "FAILED");

        // 提取交易字段（严格从交易中获取时间戳）
        String staker = (String) transaction.get("staker");
        Long amount = (Long) transaction.get("amount");
        String txId = (String) transaction.get("txId");
        Long timestamp = (Long) transaction.get("timestamp"); // 单位：秒

        // 基础校验
        if (staker == null || amount == null || txId == null || timestamp == null) {
            result.put("message", "质押数据不完整");
            return result;
        }
        if (!accountKeys.containsKey(staker)) {
            result.put("message", "质押账户不存在");
            return result;
        }
        if (amount <= 0) {
            result.put("message", "质押数量必须大于0");
            return result;
        }
        if (processedTxIds.contains(txId)) {
            result.put("message", "质押交易已处理");
            return result;
        }
        if (getTokenBalance(staker) < amount) {
            result.put("message", "代币余额不足，当前: " + getTokenBalance(staker));
            return result;
        }

        try {
            // 验证签名（使用交易中的原始时间戳）
            String stakerPubKey = Base64.getEncoder().encodeToString(accountKeys.get(staker).getPublic().getEncoded());
            if (!verifyTransaction(stakerPubKey, transaction, signature)) {
                throw new SecurityException("质押签名验证失败");
            }

            // 生成质押ID
            String stakeId = generateStakeId(staker, timestamp);

            // 执行质押（同步锁保证原子性）
            synchronized (TokenStakingContract.class) {
                // 扣减自由代币，记录质押
                tokenBalances.put(staker, getTokenBalance(staker) - amount);

                Map<String, Object> stakeRecord = new HashMap<>();
                stakeRecord.put("stakeId", stakeId);
                stakeRecord.put("staker", staker);
                stakeRecord.put("amount", amount);
                stakeRecord.put("stakeTime", timestamp);
                stakeRecord.put("reward", 0L);
                stakeRecord.put("status", "ACTIVE"); // ACTIVE/UNLOCKED
                stakes.add(stakeRecord);

                // 记录交易
                Map<String, Object> txRecord = new HashMap<>(transaction);
                txRecord.put("type", "TOKEN_STAKE");
                txRecord.put("stakeId", stakeId);
                txRecord.put("status", "SUCCESS");
                transactions.add(txRecord);
                processedTxIds.add(txId);
            }

            result.put("status", "SUCCESS");
            result.put("message", "代币质押成功");
            result.put("stakeId", stakeId);
            result.put("remainingTokens", getTokenBalance(staker));
        } catch (Exception e) {
            result.put("message", e.getMessage());
        }
        return result;
    }

    // 3. 解锁质押（含奖励计算）
    public static Map<String, Object> unlockStake(Map<String, Object> transaction, String signature) {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "FAILED");

        // 提取交易字段（时间戳来自交易）
        String staker = (String) transaction.get("staker");
        String stakeId = (String) transaction.get("stakeId");
        String txId = (String) transaction.get("txId");
        Long timestamp = (Long) transaction.get("timestamp"); // 单位：秒

        // 基础校验
        if (staker == null || stakeId == null || txId == null || timestamp == null) {
            result.put("message", "解锁数据不完整");
            return result;
        }
        if (!accountKeys.containsKey(staker)) {
            result.put("message", "账户不存在");
            return result;
        }
        if (processedTxIds.contains(txId)) {
            result.put("message", "解锁交易已处理");
            return result;
        }

        try {
            // 查找活跃质押记录
            Map<String, Object> stakeRecord = findActiveStake(stakeId, staker);
            if (stakeRecord == null) {
                throw new IllegalStateException("未找到有效质押记录");
            }

            // 校验质押时长
            long stakeTime = (long) stakeRecord.get("stakeTime");
            long stakeDuration = timestamp - stakeTime;
            if (stakeDuration < MIN_STAKE_DURATION) {
                throw new IllegalStateException("质押时长不足（需≥24小时），当前: " + stakeDuration + "秒");
            }

            // 计算奖励（按秒计息：本金 * 年化率 * 秒数 / 年总秒数）
            long stakeAmount = (long) stakeRecord.get("amount");
            long reward = (long) (stakeAmount * ANNUAL_REWARD_RATE * stakeDuration / SECONDS_PER_YEAR);

            // 验证签名
            String stakerPubKey = Base64.getEncoder().encodeToString(accountKeys.get(staker).getPublic().getEncoded());
            if (!verifyTransaction(stakerPubKey, transaction, signature)) {
                throw new SecurityException("解锁签名验证失败");
            }

            // 执行解锁（同步锁）
            synchronized (TokenStakingContract.class) {
                // 返还本金+奖励
                long total = stakeAmount + reward;
                tokenBalances.put(staker, getTokenBalance(staker) + total);

                // 更新质押状态
                stakeRecord.put("status", "UNLOCKED");
                stakeRecord.put("unlockTime", timestamp);
                stakeRecord.put("reward", reward);
                stakeRecord.put("totalReturn", total);

                // 记录交易
                Map<String, Object> txRecord = new HashMap<>(transaction);
                txRecord.put("type", "STAKE_UNLOCK");
                txRecord.put("reward", reward);
                txRecord.put("totalReturn", total);
                txRecord.put("status", "SUCCESS");
                transactions.add(txRecord);
                processedTxIds.add(txId);
            }

            result.put("status", "SUCCESS");
            result.put("message", "质押解锁成功");
            result.put("stakeAmount", stakeAmount);
            result.put("reward", reward);
            result.put("totalReturn", stakeAmount + reward);
            result.put("currentBalance", getTokenBalance(staker));
        } catch (Exception e) {
            result.put("message", e.getMessage());
        }
        return result;
    }

    // 4. 查询质押记录
    public static List<Map<String, Object>> getStakeRecords(String staker) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> stake : stakes) {
            if (staker.equals(stake.get("staker"))) {
                result.add(new HashMap<>(stake));
            }
        }
        return result;
    }

    // 辅助方法：查找活跃质押
    private static Map<String, Object> findActiveStake(String stakeId, String staker) {
        for (Map<String, Object> stake : stakes) {
            if (stakeId.equals(stake.get("stakeId"))
                    && staker.equals(stake.get("staker"))
                    && "ACTIVE".equals(stake.get("status"))) {
                return stake;
            }
        }
        return null;
    }

    // 生成质押ID
    private static String generateStakeId(String staker, long timestamp) {
        String data = staker + "STAKE" + timestamp + (transactionCounter++);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash).substring(0, 20);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("生成质押ID失败", e);
        }
    }

    // 生成交易ID
    public static String generateTxId(String address, String action) {
        String data = address + action + (System.currentTimeMillis() / 1000) + (transactionCounter++);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("生成交易ID失败", e);
        }
    }

    // 生成交易签名（使用交易中的时间戳）
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

    // 验证交易签名（严格使用交易中的时间戳）
    public static boolean verifyTransaction(String publicKeyStr, Map<String, Object> transaction, String signatureStr) {
        try {
            byte[] publicKeyBytes = Base64.getDecoder().decode(publicKeyStr);
            byte[] signatureBytes = Base64.getDecoder().decode(signatureStr);
            String txData = serializeTransaction(transaction); // 序列化包含交易中的时间戳

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

    // 序列化交易数据（包含时间戳，确保签名验证一致性）
    private static String serializeTransaction(Map<String, Object> transaction) {
        List<String> parts = new ArrayList<>();
        List<String> keys = new ArrayList<>(transaction.keySet());
        Collections.sort(keys); // 排序保证序列化一致性

        for (String key : keys) {
            if (!"status".equals(key) && !"message".equals(key) && !"type".equals(key)) {
                parts.add(key + "=" + transaction.get(key));
            }
        }
        return String.join("&", parts);
    }

    // 查询代币余额
    public static long getTokenBalance(String address) {
        return tokenBalances.getOrDefault(address, 0L);
    }

    // 测试入口
    public static void main(String[] args) {
        System.out.println("=== 启动代币质押合约 ===");

        // 1. 创建账户
        String admin = SYSTEM_ADMIN;
        String alice = "Alice";
        try {
            createAccount(admin, "ADMIN");
            createAccount(alice, "USER");
            System.out.println("\n=== 账户创建成功 ===");
            System.out.println("管理员初始代币: " + getTokenBalance(admin));
            System.out.println("Alice初始代币: " + getTokenBalance(alice));
        } catch (Exception e) {
            System.err.println("账户创建失败: " + e.getMessage());
            return;
        }

        // 2. 管理员转账给Alice（模拟代币分发）
        long transferAmount = 10000L;
        tokenBalances.put(admin, getTokenBalance(admin) - transferAmount);
        tokenBalances.put(alice, getTokenBalance(alice) + transferAmount);
        System.out.println("\n=== 管理员转账后 ===");
        System.out.println("Alice代币余额: " + getTokenBalance(alice));

        // 3. Alice质押代币（使用当前时间戳，单位：秒）
        System.out.println("\n=== Alice质押代币 ===");
        long stakeAmount = 5000L;
        long stakeTime = System.currentTimeMillis() / 1000; // 秒级时间戳
        String stakeTxId = generateTxId(alice, "STAKE");
        Map<String, Object> stakeTx = new HashMap<>();
        stakeTx.put("staker", alice);
        stakeTx.put("amount", stakeAmount);
        stakeTx.put("txId", stakeTxId);
        stakeTx.put("timestamp", stakeTime);

        String stakeSig = signTransaction(alice, stakeTx);
        Map<String, Object> stakeResult = stakeTokens(stakeTx, stakeSig);
        System.out.println("质押结果: " + stakeResult.get("message"));
        System.out.println("Alice剩余代币: " + getTokenBalance(alice));

        // 4. 模拟质押25小时后解锁（满足最小时长）
        System.out.println("\n=== 25小时后Alice解锁质押 ===");
        long unlockTime = stakeTime + 90000; // 25小时=90000秒
        String unlockTxId = generateTxId(alice, "UNLOCK");
        Map<String, Object> unlockTx = new HashMap<>();
        unlockTx.put("staker", alice);
        unlockTx.put("stakeId", stakeResult.get("stakeId"));
        unlockTx.put("txId", unlockTxId);
        unlockTx.put("timestamp", unlockTime);

        String unlockSig = signTransaction(alice, unlockTx);
        Map<String, Object> unlockResult = unlockStake(unlockTx, unlockSig);
        System.out.println("解锁结果: " + unlockResult.get("message"));
        System.out.println("获得奖励: " + unlockResult.get("reward"));
        System.out.println("解锁后总余额: " + unlockResult.get("currentBalance"));
    }
}