package com.bit.solana.vmt;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Base64;

public class PropertyTokenContract {
    // 核心存储结构
    private static final Map<String, KeyPair> accountKeys = new ConcurrentHashMap<>();
    private static final Map<String, Long> tokenBalances = new ConcurrentHashMap<>(); // 代币余额: 地址 -> 数量
    private static final Map<String, Long> fundBalances = new ConcurrentHashMap<>();  // 资金余额: 地址 -> 数量
    private static final List<Map<String, Object>> transactions = new ArrayList<>();  // 交易记录
    private static final Set<String> processedTxIds = new HashSet<>();
    private static final Map<String, Object> propertyInfo = new ConcurrentHashMap<>(); // 房产信息

    // 系统参数
    private static final String CURVE_NAME = "secp256r1";
    private static final String SIGNATURE_ALGORITHM = "SHA256withECDSA";
    private static final long TOTAL_TOKEN_SUPPLY = 100000000L; // 1亿代币总发行量
    private static final String TOKEN_NAME = "PropertyBackedToken";
    private static final String TOKEN_SYMBOL = "PBT";
    private static long transactionCounter = 0;
    private static final String PROPERTY_OWNER = "PropertyAdmin"; // 房产初始所有者
    private static long totalFund = 0L; // 总募集资金

    // 初始化房产及代币信息
    static {
        propertyInfo.put("name", "城市中心商业大厦");
        propertyInfo.put("location", "市中心金融区88号");
        propertyInfo.put("valuation", 500000000L); // 房产估值5亿元
        propertyInfo.put("totalTokens", TOTAL_TOKEN_SUPPLY);
        propertyInfo.put("remainingTokens", TOTAL_TOKEN_SUPPLY);
        propertyInfo.put("tokenPrice", 5L); // 每个代币对应5元房产价值
        propertyInfo.put("status", "ACTIVE");
        propertyInfo.put("launchTime", System.currentTimeMillis());
    }

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
            tokenBalances.putIfAbsent(address, 0L);
            fundBalances.putIfAbsent(address, 0L);

            // 初始化房产管理员账户并分配所有代币
            if (PROPERTY_OWNER.equals(address)) {
                tokenBalances.put(address, TOTAL_TOKEN_SUPPLY);
            }

            return Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
        } catch (Exception e) {
            throw new RuntimeException("账户创建失败", e);
        }
    }

    // 获取代币余额
    public static long getTokenBalance(String address) {
        return tokenBalances.getOrDefault(address, 0L);
    }

    // 获取资金余额
    public static long getFundBalance(String address) {
        return fundBalances.getOrDefault(address, 0L);
    }

    // 购买代币
    public static Map<String, Object> buyTokens(Map<String, Object> transaction, String signature) {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "FAILED");

        // 提取交易字段
        String buyer = (String) transaction.get("buyer");
        Long tokenAmount = (Long) transaction.get("tokenAmount");
        String txId = (String) transaction.get("txId");
        Long timestamp = (Long) transaction.get("timestamp");

        // 验证必要字段
        if (buyer == null || tokenAmount == null || txId == null || timestamp == null) {
            result.put("message", "交易数据不完整");
            return result;
        }

        // 验证账户存在
        if (!accountKeys.containsKey(buyer) || !accountKeys.containsKey(PROPERTY_OWNER)) {
            result.put("message", "账户不存在");
            return result;
        }

        // 验证合约状态
        if (!"ACTIVE".equals(propertyInfo.get("status"))) {
            result.put("message", "合约已终止");
            return result;
        }

        // 验证数量有效性
        if (tokenAmount <= 0) {
            result.put("message", "购买数量必须大于0");
            return result;
        }

        // 检查交易是否已处理
        if (processedTxIds.contains(txId)) {
            result.put("message", "该交易已处理");
            return result;
        }

        try {
            // 计算所需资金
            long tokenPrice = (long) propertyInfo.get("tokenPrice");
            long requiredFund = tokenAmount * tokenPrice;

            // 验证买家资金是否充足
            if (getFundBalance(buyer) < requiredFund) {
                throw new IllegalStateException("资金不足，需要: " + requiredFund + "，当前: " + getFundBalance(buyer));
            }

            // 验证剩余代币是否充足
            long remainingTokens = (long) propertyInfo.get("remainingTokens");
            if (remainingTokens < tokenAmount) {
                throw new IllegalStateException("代币不足，剩余: " + remainingTokens);
            }

            // 验证签名（使用交易中的原始时间戳）
            String buyerPubKey = Base64.getEncoder().encodeToString(
                    accountKeys.get(buyer).getPublic().getEncoded());
            if (!verifyTransaction(buyerPubKey, transaction, signature)) {
                throw new SecurityException("签名验证失败");
            }

            // 执行交易
            synchronized (PropertyTokenContract.class) {
                // 扣减买家资金
                fundBalances.put(buyer, getFundBalance(buyer) - requiredFund);

                // 增加管理员资金
                fundBalances.put(PROPERTY_OWNER, getFundBalance(PROPERTY_OWNER) + requiredFund);

                // 转移代币
                tokenBalances.put(PROPERTY_OWNER, getTokenBalance(PROPERTY_OWNER) - tokenAmount);
                tokenBalances.put(buyer, getTokenBalance(buyer) + tokenAmount);

                // 更新系统状态
                propertyInfo.put("remainingTokens", remainingTokens - tokenAmount);
                totalFund += requiredFund;

                // 记录交易
                Map<String, Object> txRecord = new HashMap<>(transaction);
                txRecord.put("type", "BUY");
                txRecord.put("fundAmount", requiredFund);
                txRecord.put("status", "SUCCESS");
                transactions.add(txRecord);
                processedTxIds.add(txId);
            }

            result.put("status", "SUCCESS");
            result.put("message", "代币购买成功");
            result.put("tokenAmount", tokenAmount);
            result.put("fundSpent", tokenAmount * tokenPrice);
            result.put("remainingTokens", propertyInfo.get("remainingTokens"));
        } catch (Exception e) {
            result.put("message", e.getMessage());
        }

        return result;
    }

    // 分配收益（由管理员执行）
    public static Map<String, Object> distributeProfit(Map<String, Object> transaction, String signature) {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "FAILED");

        // 提取交易字段
        String admin = (String) transaction.get("admin");
        Long profitAmount = (Long) transaction.get("profitAmount");
        String txId = (String) transaction.get("txId");
        Long timestamp = (Long) transaction.get("timestamp");

        // 验证必要字段
        if (admin == null || profitAmount == null || txId == null || timestamp == null) {
            result.put("message", "交易数据不完整");
            return result;
        }

        // 验证管理员身份
        if (!PROPERTY_OWNER.equals(admin)) {
            result.put("message", "无权限执行此操作，需管理员身份");
            return result;
        }

        // 验证账户存在
        if (!accountKeys.containsKey(admin)) {
            result.put("message", "管理员账户不存在");
            return result;
        }

        // 验证金额有效性
        if (profitAmount <= 0) {
            result.put("message", "收益金额必须大于0");
            return result;
        }

        // 验证管理员资金是否充足
        if (getFundBalance(admin) < profitAmount) {
            result.put("message", "管理员资金不足，无法分配收益");
            return result;
        }

        // 检查交易是否已处理
        if (processedTxIds.contains(txId)) {
            result.put("message", "该交易已处理");
            return result;
        }

        try {
            // 验证签名
            String adminPubKey = Base64.getEncoder().encodeToString(
                    accountKeys.get(admin).getPublic().getEncoded());
            if (!verifyTransaction(adminPubKey, transaction, signature)) {
                throw new SecurityException("签名验证失败");
            }

            // 计算总流通代币
            long circulatingTokens = TOTAL_TOKEN_SUPPLY - (long) propertyInfo.get("remainingTokens");
            if (circulatingTokens <= 0) {
                throw new IllegalStateException("无流通代币可分配收益");
            }

            // 执行收益分配
            synchronized (PropertyTokenContract.class) {
                // 扣减管理员资金
                fundBalances.put(admin, getFundBalance(admin) - profitAmount);

                // 按持有比例分配收益
                for (String holder : tokenBalances.keySet()) {
                    if (holder.equals(PROPERTY_OWNER)) continue; // 排除管理员

                    long holderTokens = getTokenBalance(holder);
                    if (holderTokens <= 0) continue;

                    // 计算应得收益
                    long holderProfit = (holderTokens * profitAmount) / circulatingTokens;
                    if (holderProfit > 0) {
                        fundBalances.put(holder, getFundBalance(holder) + holderProfit);
                    }
                }

                // 记录交易
                Map<String, Object> txRecord = new HashMap<>(transaction);
                txRecord.put("type", "PROFIT_DISTRIBUTION");
                txRecord.put("circulatingTokens", circulatingTokens);
                txRecord.put("status", "SUCCESS");
                transactions.add(txRecord);
                processedTxIds.add(txId);
            }

            result.put("status", "SUCCESS");
            result.put("message", "收益分配成功");
            result.put("totalProfit", profitAmount);
            result.put("circulatingTokens", circulatingTokens);
        } catch (Exception e) {
            result.put("message", e.getMessage());
        }

        return result;
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

    // 生成交易ID
    public static String generateTxId(String address, String action) {
        String data = address + action + System.currentTimeMillis() + (transactionCounter++);
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
            if (!"status".equals(key) && !"message".equals(key) && !"type".equals(key)) {
                parts.add(key + "=" + transaction.get(key));
            }
        }
        return String.join("&", parts);
    }

    // 获取房产及代币状态
    public static Map<String, Object> getPropertyStatus() {
        return new HashMap<>(propertyInfo);
    }

    // 获取交易记录
    public static List<Map<String, Object>> getTransactionRecords() {
        return new ArrayList<>(transactions);
    }

    // 测试入口
    public static void main(String[] args) {
        System.out.println("=== 启动房产代币化RWA合约 ===");

        // 1. 创建测试账户
        String admin = PROPERTY_OWNER;
        String alice = "Alice";
        String bob = "Bob";
        try {
            String adminPubKey = createAccount(admin);
            String alicePubKey = createAccount(alice);
            String bobPubKey = createAccount(bob);
            System.out.println("\n=== 账户创建成功 ===");
            System.out.println(admin + " 公钥: " + adminPubKey.substring(0, 30) + "...");
            System.out.println(alice + " 公钥: " + alicePubKey.substring(0, 30) + "...");
            System.out.println(bob + " 公钥: " + bobPubKey.substring(0, 30) + "...");

            // 给账户充值资金
            fundBalances.put(alice, 1000000L);  // 100万
            fundBalances.put(bob, 1500000L);    // 150万
            System.out.println(alice + " 初始资金: " + getFundBalance(alice));
            System.out.println(bob + " 初始资金: " + getFundBalance(bob));
            System.out.println(admin + " 初始代币: " + getTokenBalance(admin));
        } catch (Exception e) {
            System.err.println("账户创建失败: " + e.getMessage());
            return;
        }

        // 2. 显示初始状态
        System.out.println("\n=== 初始合约状态 ===");
        Map<String, Object> initialStatus = getPropertyStatus();
        System.out.println("房产名称: " + initialStatus.get("name"));
        System.out.println("总代币量: " + initialStatus.get("totalTokens"));
        System.out.println("剩余代币: " + initialStatus.get("remainingTokens"));
        System.out.println("代币价格: " + initialStatus.get("tokenPrice") + " 元/个");

        // 3. Alice购买代币
        System.out.println("\n=== Alice 购买代币 ===");
        long aliceTokens = 50000L; // 5万个
        String aliceTxId = generateTxId(alice, "BUY");
        long aliceTime = System.currentTimeMillis();
        Map<String, Object> aliceTx = new HashMap<>();
        aliceTx.put("buyer", alice);
        aliceTx.put("tokenAmount", aliceTokens);
        aliceTx.put("txId", aliceTxId);
        aliceTx.put("timestamp", aliceTime); // 签名时间取自交易

        String aliceSignature = signTransaction(alice, aliceTx);
        Map<String, Object> aliceResult = buyTokens(aliceTx, aliceSignature);
        System.out.println("购买结果: " + aliceResult.get("message"));
        System.out.println("Alice 代币余额: " + getTokenBalance(alice));
        System.out.println("Alice 资金余额: " + getFundBalance(alice));

        // 4. Bob购买代币
        System.out.println("\n=== Bob 购买代币 ===");
        long bobTokens = 80000L; // 8万个
        String bobTxId = generateTxId(bob, "BUY");
        long bobTime = System.currentTimeMillis();
        Map<String, Object> bobTx = new HashMap<>();
        bobTx.put("buyer", bob);
        bobTx.put("tokenAmount", bobTokens);
        bobTx.put("txId", bobTxId);
        bobTx.put("timestamp", bobTime); // 签名时间取自交易

        String bobSignature = signTransaction(bob, bobTx);
        Map<String, Object> bobResult = buyTokens(bobTx, bobSignature);
        System.out.println("购买结果: " + bobResult.get("message"));
        System.out.println("Bob 代币余额: " + getTokenBalance(bob));
        System.out.println("Bob 资金余额: " + getFundBalance(bob));

        // 5. 管理员分配收益
        System.out.println("\n=== 管理员分配收益 ===");
        long profit = 260000L; // 26万收益
        String profitTxId = generateTxId(admin, "PROFIT");
        long profitTime = System.currentTimeMillis();
        Map<String, Object> profitTx = new HashMap<>();
        profitTx.put("admin", admin);
        profitTx.put("profitAmount", profit);
        profitTx.put("txId", profitTxId);
        profitTx.put("timestamp", profitTime); // 签名时间取自交易

        String profitSignature = signTransaction(admin, profitTx);
        Map<String, Object> profitResult = distributeProfit(profitTx, profitSignature);
        System.out.println("收益分配结果: " + profitResult.get("message"));
        System.out.println("Alice 获得收益后资金: " + getFundBalance(alice));
        System.out.println("Bob 获得收益后资金: " + getFundBalance(bob));

        // 6. 显示最终状态
        System.out.println("\n=== 最终合约状态 ===");
        Map<String, Object> finalStatus = getPropertyStatus();
        System.out.println("剩余代币: " + finalStatus.get("remainingTokens"));
        System.out.println("总募集资金: " + totalFund);

        // 7. 显示交易记录
        System.out.println("\n=== 交易记录 ===");
        List<Map<String, Object>> records = getTransactionRecords();
        for (Map<String, Object> record : records) {
            System.out.println(record.get("type") + " - " + record.get("txId") +
                    " - 时间: " + new Date((Long) record.get("timestamp")));
        }
    }
}