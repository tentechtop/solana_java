package com.bit.solana.vmt;


import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Base64;

public class HopeSchoolCharityContract {
    // 核心存储结构
    private static final Map<String, Long> accountBalances = new ConcurrentHashMap<>();
    private static final Map<String, KeyPair> accountKeys = new ConcurrentHashMap<>();
    private static final List<Map<String, Object>> donations = new ArrayList<>(); // 捐赠记录
    private static final Set<String> processedTransactions = new HashSet<>();
    private static final Map<String, Object> charityInfo = new ConcurrentHashMap<>(); // 募捐基本信息

    // 系统参数
    private static final String CURVE_NAME = "secp256r1";
    private static final String SIGNATURE_ALGORITHM = "SHA256withECDSA";
    private static long transactionCounter = 0;
    private static final long TARGET_AMOUNT = 100000000L; // 1亿目标金额
    private static boolean contractActive = true; // 合约状态

    // 初始化募捐信息
    static {
        charityInfo.put("name", "希望小学建设募捐");
        charityInfo.put("target", TARGET_AMOUNT);
        charityInfo.put("currentAmount", 0L);
        charityInfo.put("startTime", System.currentTimeMillis());
        charityInfo.put("endTime", 0L);
        charityInfo.put("status", "ACTIVE");
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


    // 捐赠操作（修改参数，接收完整交易数据）
    public static Map<String, Object> donate(Map<String, Object> transaction, String signature) {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "FAILED");

        // 从交易数据中提取必要字段
        String donorAddress = (String) transaction.get("donor");
        Long amount = (Long) transaction.get("amount");
        String txId = (String) transaction.get("txId");
        if (donorAddress == null || amount == null || txId == null) {
            result.put("message", "交易数据不完整");
            return result;
        }

        // 检查合约状态
        if (!contractActive || !"ACTIVE".equals(charityInfo.get("status"))) {
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
            // 验证捐赠者签名（直接使用传入的交易数据，包含签名时的timestamp）
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
                long currentAmount = (long) charityInfo.get("currentAmount");
                long newAmount = currentAmount + amount;
                charityInfo.put("currentAmount", newAmount);

                // 记录捐赠信息（使用签名时的交易数据）
                Map<String, Object> donationRecord = new HashMap<>(transaction);
                donationRecord.put("status", "SUCCESS");
                donations.add(donationRecord);
                processedTransactions.add(txId);

                // 检查是否达到目标
                if (newAmount >= TARGET_AMOUNT) {
                    terminateContract();
                    result.put("message", "捐赠成功！感谢您的爱心，募捐已达到目标金额");
                } else {
                    result.put("message", "捐赠成功！感谢您的爱心");
                }

                result.put("status", "SUCCESS");
                result.put("donationId", txId);
                result.put("currentTotal", newAmount);
                result.put("remaining", Math.max(0, TARGET_AMOUNT - newAmount));
            }
        } catch (Exception e) {
            result.put("message", e.getMessage());
        }

        return result;
    }


    // 终止合约
    private static void terminateContract() {
        contractActive = false;
        charityInfo.put("status", "COMPLETED");
        charityInfo.put("endTime", System.currentTimeMillis());
        charityInfo.put("finalAmount", charityInfo.get("currentAmount"));
    }

    // 获取募捐状态
    public static Map<String, Object> getCharityStatus() {
        return new HashMap<>(charityInfo);
    }

    // 获取捐赠记录
    public static List<Map<String, Object>> getDonationRecords() {
        return new ArrayList<>(donations);
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
    public static String generateTxId(String donor, long amount) {
        String data = donor + "DONATION" + amount + System.currentTimeMillis() + (transactionCounter++);
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

    // 测试入口
    public static void main(String[] args) {
        System.out.println("=== 启动希望小学募捐智能合约 ===");

        // 1. 创建测试账户
        String alice = "Alice";
        String bob = "Bob";
        String charlie = "Charlie";
        try {
            String alicePubKey = createAccount(alice);
            String bobPubKey = createAccount(bob);
            String charliePubKey = createAccount(charlie);
            System.out.println("\n=== 账户创建成功 ===");
            System.out.println(alice + " 公钥: " + alicePubKey.substring(0, 30) + "...");
            System.out.println(bob + " 公钥: " + bobPubKey.substring(0, 30) + "...");
            System.out.println(charlie + " 公钥: " + charliePubKey.substring(0, 30) + "...");

            // 给账户充值
            accountBalances.put(alice, 50000000L);  // 5000万
            accountBalances.put(bob, 60000000L);    // 6000万
            accountBalances.put(charlie, 20000000L); // 2000万
            System.out.println(alice + " 初始余额: " + getBalance(alice));
            System.out.println(bob + " 初始余额: " + getBalance(bob));
            System.out.println(charlie + " 初始余额: " + getBalance(charlie));
        } catch (Exception e) {
            System.err.println("账户创建失败: " + e.getMessage());
            return;
        }

        // 2. 显示初始募捐状态
        System.out.println("\n=== 初始募捐状态 ===");
        Map<String, Object> initialStatus = getCharityStatus();
        System.out.println("募捐名称: " + initialStatus.get("name"));
        System.out.println("目标金额: " + initialStatus.get("target") + " 元");
        System.out.println("当前金额: " + initialStatus.get("currentAmount") + " 元");
        System.out.println("状态: " + initialStatus.get("status"));


        // 3. Alice 捐赠（修改后）
        System.out.println("\n=== Alice 捐赠 ===");
        long aliceAmount = 30000000L; // 3000万
        String aliceTxId = generateTxId(alice, aliceAmount);
        Map<String, Object> aliceDonation = new HashMap<>();
        aliceDonation.put("donor", alice);
        aliceDonation.put("amount", aliceAmount);
        aliceDonation.put("txId", aliceTxId);
        aliceDonation.put("timestamp", System.currentTimeMillis()); // 签名时的时间戳
        String aliceSignature = signTransaction(alice, aliceDonation);

        // 直接传递完整交易数据给donate方法
        Map<String, Object> aliceResult = donate(aliceDonation, aliceSignature);
        System.out.println("捐赠结果: " + aliceResult.get("message"));
        System.out.println("当前总额: " + aliceResult.get("currentTotal") + " 元");
        System.out.println("剩余金额: " + aliceResult.get("remaining") + " 元");
        System.out.println(alice + " 剩余余额: " + getBalance(alice));


        // 4. Bob 捐赠（同理修改）
        System.out.println("\n=== Bob 捐赠 ===");
        long bobAmount = 60000000L; // 6000万
        String bobTxId = generateTxId(bob, bobAmount);
        Map<String, Object> bobDonation = new HashMap<>();
        bobDonation.put("donor", bob);
        bobDonation.put("amount", bobAmount);
        bobDonation.put("txId", bobTxId);
        bobDonation.put("timestamp", System.currentTimeMillis());
        String bobSignature = signTransaction(bob, bobDonation);
        Map<String, Object> bobResult = donate(bobDonation, bobSignature);
        System.out.println("捐赠结果: " + bobResult.get("message"));
        System.out.println("当前总额: " + bobResult.get("currentTotal") + " 元");
        System.out.println("Bob 剩余余额: " + getBalance(bob));


        // 5. Charlie 尝试捐赠（同理修改）
        System.out.println("\n=== Charlie 尝试捐赠 ===");
        long charlieAmount = 10000000L; // 1000万
        String charlieTxId = generateTxId(charlie, charlieAmount);
        Map<String, Object> charlieDonation = new HashMap<>();
        charlieDonation.put("donor", charlie);
        charlieDonation.put("amount", charlieAmount);
        charlieDonation.put("txId", charlieTxId);
        charlieDonation.put("timestamp", System.currentTimeMillis());
        String charlieSignature = signTransaction(charlie, charlieDonation);
        Map<String, Object> charlieResult = donate(charlieDonation, charlieSignature);
        // 6. 显示最终募捐状态
        System.out.println("\n=== 最终募捐状态 ===");
        Map<String, Object> finalStatus = getCharityStatus();
        System.out.println("最终金额: " + finalStatus.get("finalAmount") + " 元");
        System.out.println("状态: " + finalStatus.get("status"));
        System.out.println("开始时间: " + new Date((long) finalStatus.get("startTime")));
        System.out.println("结束时间: " + new Date((long) finalStatus.get("endTime")));

        // 7. 显示捐赠记录
        System.out.println("\n=== 捐赠记录 ===");
        List<Map<String, Object>> records = getDonationRecords();
        for (Map<String, Object> record : records) {
            System.out.println(record.get("donor") + " 捐赠了 " + record.get("amount") + " 元，时间: " +
                    new Date((long) record.get("timestamp")));
        }
    }
}