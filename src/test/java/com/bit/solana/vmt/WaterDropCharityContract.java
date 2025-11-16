package com.bit.solana.vmt;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Base64;

public class WaterDropCharityContract {
    // 核心存储结构
    private static final Map<String, Long> accountBalances = new ConcurrentHashMap<>();
    private static final Map<String, KeyPair> accountKeys = new ConcurrentHashMap<>();
    private static final List<Map<String, Object>> donations = new ArrayList<>(); // 捐赠记录
    private static final Set<String> processedTransactions = new HashSet<>();
    private static final Map<String, Map<String, Object>> fundraisers = new ConcurrentHashMap<>(); // 募捐项目
    private static final Map<String, List<String>> fundraiserMaterials = new ConcurrentHashMap<>(); // 募捐材料

    // 系统参数
    private static final String CURVE_NAME = "secp256r1";
    private static final String SIGNATURE_ALGORITHM = "SHA256withECDSA";
    private static long transactionCounter = 0;

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


    // 修正：接收完整交易数据，而非零散字段
    public static Map<String, Object> createFundraiser(Map<String, Object> fundraiserTx,
                                                       List<String> materialHashes, String signature) {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "FAILED");

        // 从交易中提取字段并校验
        String initiator = (String) fundraiserTx.get("initiator");
        String reason = (String) fundraiserTx.get("reason");
        Long targetAmount = (Long) fundraiserTx.get("targetAmount");
        Long endTime = (Long) fundraiserTx.get("endTime");
        Long timestamp = (Long) fundraiserTx.get("timestamp");

        // 校验字段有效性
        if (initiator == null || reason == null || targetAmount == null || targetAmount <= 0
                || endTime == null || endTime <= System.currentTimeMillis() || timestamp == null) {
            result.put("message", "募捐信息不完整或无效");
            return result;
        }

        if (!accountKeys.containsKey(initiator)) {
            result.put("message", "发起人账户不存在");
            return result;
        }

        try {
            // 验证发起人签名：使用传入的完整交易数据（包含签名时的timestamp）
            String publicKey = Base64.getEncoder().encodeToString(
                    accountKeys.get(initiator).getPublic().getEncoded());
            if (!verifyTransaction(publicKey, fundraiserTx, signature)) {
                result.put("message", "签名验证失败");
                return result;
            }

            // 生成募捐项目ID（使用交易中的timestamp，确保一致性）
            String fundraiserId = generateFundraiserId(initiator, timestamp);

            // 存储募捐项目信息（后续逻辑不变）
            Map<String, Object> fundraiser = new HashMap<>();
            fundraiser.put("id", fundraiserId);
            fundraiser.put("initiator", initiator);
            fundraiser.put("reason", reason);
            fundraiser.put("targetAmount", targetAmount);
            fundraiser.put("currentAmount", 0L);
            fundraiser.put("startTime", timestamp); // 使用交易中的时间戳作为开始时间
            fundraiser.put("endTime", endTime);
            fundraiser.put("status", "ACTIVE");

            fundraisers.put(fundraiserId, fundraiser);
            if (materialHashes != null && !materialHashes.isEmpty()) {
                fundraiserMaterials.put(fundraiserId, new ArrayList<>(materialHashes));
            }

            result.put("status", "SUCCESS");
            result.put("fundraiserId", fundraiserId);
            result.put("message", "募捐项目创建成功");
        } catch (Exception e) {
            result.put("message", e.getMessage());
        }

        return result;
    }


    // 捐赠操作
    public static Map<String, Object> donate(Map<String, Object> transaction, String signature) {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "FAILED");

        // 从交易数据中提取必要字段
        String donorAddress = (String) transaction.get("donor");
        Long amount = (Long) transaction.get("amount");
        String txId = (String) transaction.get("txId");
        String fundraiserId = (String) transaction.get("fundraiserId");

        if (donorAddress == null || amount == null || txId == null || fundraiserId == null) {
            result.put("message", "交易数据不完整");
            return result;
        }

        // 检查募捐项目是否存在
        Map<String, Object> fundraiser = fundraisers.get(fundraiserId);
        if (fundraiser == null) {
            result.put("message", "募捐项目不存在");
            return result;
        }

        // 检查募捐状态
        String status = (String) fundraiser.get("status");
        long endTime = (long) fundraiser.get("endTime");
        if (!"ACTIVE".equals(status) || System.currentTimeMillis() > endTime) {
            result.put("message", "募捐已结束，感谢您的关注");
            return result;
        }

        // 检查金额有效性
        if (amount <= 0) {
            result.put("message", "捐赠金额必须大于0");
            return result;
        }

        // 检查交易是否已处理
        if (processedTransactions.contains(txId)) {
            result.put("message", "该捐赠已处理（防重复捐赠）");
            return result;
        }

        try {
            // 验证捐赠者签名
            String donorPublicKey = Base64.getEncoder().encodeToString(
                    accountKeys.get(donorAddress).getPublic().getEncoded());
            if (!verifyTransaction(donorPublicKey, transaction, signature)) {
                throw new SecurityException("签名验证失败");
            }

            // 验证余额并转账
            synchronized (accountBalances) {
                long donorBalance = getBalance(donorAddress);
                if (donorBalance < amount) {
                    throw new IllegalStateException("余额不足，当前余额: " + donorBalance + "，需捐赠: " + amount);
                }

                // 从捐赠者账户扣除金额
                accountBalances.put(donorAddress, donorBalance - amount);

                // 更新募捐总额
                long currentAmount = (long) fundraiser.get("currentAmount");
                long newAmount = currentAmount + amount;
                fundraiser.put("currentAmount", newAmount);

                // 检查是否达到目标或已结束
                if (newAmount >= (long) fundraiser.get("targetAmount")) {
                    fundraiser.put("status", "COMPLETED");
                } else if (System.currentTimeMillis() > endTime) {
                    fundraiser.put("status", "EXPIRED");
                }

                // 记录捐赠信息
                Map<String, Object> donationRecord = new HashMap<>(transaction);
                donationRecord.put("status", "SUCCESS");
                donationRecord.put("timestamp", System.currentTimeMillis());
                donations.add(donationRecord);
                processedTransactions.add(txId);

                result.put("status", "SUCCESS");
                result.put("donationId", txId);
                result.put("currentTotal", newAmount);
                result.put("remaining", Math.max(0, (long) fundraiser.get("targetAmount") - newAmount));
                result.put("message", "捐赠成功！感谢您的爱心");
            }
        } catch (Exception e) {
            result.put("message", e.getMessage());
        }

        return result;
    }

    // 获取募捐项目信息
    public static Map<String, Object> getFundraiserInfo(String fundraiserId) {
        Map<String, Object> fundraiser = fundraisers.get(fundraiserId);
        if (fundraiser == null) {
            return null;
        }

        Map<String, Object> info = new HashMap<>(fundraiser);
        info.put("materials", fundraiserMaterials.getOrDefault(fundraiserId, Collections.emptyList()));
        return info;
    }

    // 获取所有募捐项目
    public static List<Map<String, Object>> getAllFundraisers() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> fundraiser : fundraisers.values()) {
            Map<String, Object> info = new HashMap<>(fundraiser);
            info.put("materials", fundraiserMaterials.getOrDefault(fundraiser.get("id"), Collections.emptyList()));
            result.add(info);
        }
        return result;
    }

    // 获取捐赠记录
    public static List<Map<String, Object>> getDonationRecords(String fundraiserId) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> donation : donations) {
            if (fundraiserId.equals(donation.get("fundraiserId"))) {
                result.add(new HashMap<>(donation));
            }
        }
        return result;
    }

    // 获取个人捐赠记录
    public static List<Map<String, Object>> getPersonalDonations(String address) {
        List<Map<String, Object>> personal = new ArrayList<>();
        for (Map<String, Object> donation : donations) {
            if (address.equals(donation.get("donor"))) {
                personal.add(new HashMap<>(donation));
            }
        }
        return personal;
    }

    // 生成交易ID
    public static String generateTxId(String donor, String fundraiserId, long amount) {
        String data = donor + fundraiserId + "DONATION" + amount + System.currentTimeMillis() + (transactionCounter++);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("生成交易ID失败", e);
        }
    }

    // 生成募捐项目ID
    public static String generateFundraiserId(String initiator, long timestamp) {
        String data = initiator + "FUNDRAISER" + timestamp + (transactionCounter++);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("生成募捐项目ID失败", e);
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

    // 测试入口
    public static void main(String[] args) {
        System.out.println("=== 启动水滴筹智能合约 ===");

        // 1. 创建测试账户
        String patient = "张先生";
        String donor1 = "李女士";
        String donor2 = "王先生";

        try {
            String patientPubKey = createAccount(patient);
            String donor1PubKey = createAccount(donor1);
            String donor2PubKey = createAccount(donor2);
            System.out.println("\n=== 账户创建成功 ===");
            System.out.println(patient + " 公钥: " + patientPubKey.substring(0, 30) + "...");
            System.out.println(donor1 + " 公钥: " + donor1PubKey.substring(0, 30) + "...");
            System.out.println(donor2 + " 公钥: " + donor2PubKey.substring(0, 30) + "...");

            // 给捐赠者账户充值
            accountBalances.put(donor1, 50000L);  // 5万元
            accountBalances.put(donor2, 100000L); // 10万元
            System.out.println(donor1 + " 初始余额: " + getBalance(donor1));
            System.out.println(donor2 + " 初始余额: " + getBalance(donor2));
        } catch (Exception e) {
            System.err.println("账户创建失败: " + e.getMessage());
            return;
        }

        // 2. 创建募捐项目 - 张先生因重病需要手术费
        System.out.println("\n=== 创建募捐项目 ===");
        String reason = "本人不幸患上急性白血病，需要进行骨髓移植手术...";
        long targetAmount = 300000L;
        long endTime = System.currentTimeMillis() + 30 * 24 * 60 * 60 * 1000L;

        List<String> materials = Arrays.asList(
                "medicalReportHash123456",
                "idCardHash789012",
                "hospitalBillHash345678"
        );


        // 构造签名用的交易（包含固定的timestamp）
        Map<String, Object> createTx = new HashMap<>();
        createTx.put("type", "CREATE_FUNDRAISER");
        createTx.put("initiator", patient);
        createTx.put("reason", reason);
        createTx.put("targetAmount", targetAmount);
        createTx.put("endTime", endTime);
        createTx.put("timestamp", System.currentTimeMillis()); // 签名时的时间戳
        String createSignature = signTransaction(patient, createTx); // 基于此交易签名

// 传入完整交易到createFundraiser，确保验证数据与签名数据一致
        Map<String, Object> createResult = createFundraiser(createTx, materials, createSignature);
        System.out.println("创建结果: " + createResult.get("message"));
        String fundraiserId = (String) createResult.get("fundraiserId");
        System.out.println("募捐项目ID: " + fundraiserId);

        // 3. 显示募捐项目信息
        System.out.println("\n=== 募捐项目信息 ===");
        Map<String, Object> fundraiserInfo = getFundraiserInfo(fundraiserId);
        System.out.println("发起人: " + fundraiserInfo.get("initiator"));
        System.out.println("募捐原因: " + fundraiserInfo.get("reason"));
        System.out.println("目标金额: " + fundraiserInfo.get("targetAmount") + " 元");
        System.out.println("当前金额: " + fundraiserInfo.get("currentAmount") + " 元");
        System.out.println("结束时间: " + new Date((long) fundraiserInfo.get("endTime")));
        System.out.println("提交材料数: " + ((List<?>) fundraiserInfo.get("materials")).size());

        // 4. 李女士捐赠
        System.out.println("\n=== 李女士捐赠 ===");
        long amount1 = 10000L; // 1万元
        String txId1 = generateTxId(donor1, fundraiserId, amount1);
        Map<String, Object> donation1 = new HashMap<>();
        donation1.put("donor", donor1);
        donation1.put("amount", amount1);
        donation1.put("txId", txId1);
        donation1.put("fundraiserId", fundraiserId);
        donation1.put("message", "早日康复！"); // 捐赠留言
        String signature1 = signTransaction(donor1, donation1);

        Map<String, Object> result1 = donate(donation1, signature1);
        System.out.println("捐赠结果: " + result1.get("message"));
        System.out.println("当前总额: " + result1.get("currentTotal") + " 元");
        System.out.println("剩余金额: " + result1.get("remaining") + " 元");
        System.out.println(donor1 + " 剩余余额: " + getBalance(donor1));

        // 5. 王先生捐赠
        System.out.println("\n=== 王先生捐赠 ===");
        long amount2 = 20000L; // 2万元
        String txId2 = generateTxId(donor2, fundraiserId, amount2);
        Map<String, Object> donation2 = new HashMap<>();
        donation2.put("donor", donor2);
        donation2.put("amount", amount2);
        donation2.put("txId", txId2);
        donation2.put("fundraiserId", fundraiserId);
        donation2.put("message", "加油，会好起来的！");
        String signature2 = signTransaction(donor2, donation2);

        Map<String, Object> result2 = donate(donation2, signature2);
        System.out.println("捐赠结果: " + result2.get("message"));
        System.out.println("当前总额: " + result2.get("currentTotal") + " 元");
        System.out.println(donor2 + " 剩余余额: " + getBalance(donor2));

        // 6. 显示捐赠记录
        System.out.println("\n=== 捐赠记录 ===");
        List<Map<String, Object>> records = getDonationRecords(fundraiserId);
        for (Map<String, Object> record : records) {
            System.out.println(record.get("donor") + " 捐赠了 " + record.get("amount") + " 元，留言: " +
                    record.get("message") + "，时间: " + new Date((long) record.get("timestamp")));
        }

        // 7. 显示更新后的募捐状态
        System.out.println("\n=== 更新后的募捐状态 ===");
        Map<String, Object> updatedInfo = getFundraiserInfo(fundraiserId);
        System.out.println("当前金额: " + updatedInfo.get("currentAmount") + " 元");
        System.out.println("目标金额: " + updatedInfo.get("targetAmount") + " 元");
        System.out.println("完成比例: " +
                (double)(long)updatedInfo.get("currentAmount") / (long)updatedInfo.get("targetAmount") * 100 + "%");
    }
}