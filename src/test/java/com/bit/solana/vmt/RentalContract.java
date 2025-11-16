package com.bit.solana.vmt;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Base64;

public class RentalContract {
    // 核心存储结构
    private static final Map<String, Long> accountBalances = new ConcurrentHashMap<>();
    private static final Map<String, KeyPair> accountKeys = new ConcurrentHashMap<>();
    private static final Map<String, Map<String, Object>> properties = new ConcurrentHashMap<>(); // 房屋信息
    private static final Map<String, Map<String, Object>> rentalAgreements = new ConcurrentHashMap<>(); // 租赁协议
    private static final List<Map<String, Object>> transactionHistory = new ArrayList<>();
    private static final Set<String> processedTransactions = new HashSet<>();

    // 系统参数
    private static final String CURVE_NAME = "secp256r1";
    private static final String SIGNATURE_ALGORITHM = "SHA256withECDSA";
    private static long transactionCounter = 0;
    private static long agreementCounter = 0;

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

    // 发布房屋信息
    public static Map<String, Object> listProperty(String ownerAddress, Map<String, Object> propertyInfo, String signature) {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "FAILED");

        String propertyId = (String) propertyInfo.get("propertyId");
        if (properties.containsKey(propertyId)) {
            result.put("message", "房屋ID已存在");
            return result;
        }

        try {
            // 验证所有者签名
            String ownerPublicKey = Base64.getEncoder().encodeToString(accountKeys.get(ownerAddress).getPublic().getEncoded());
            if (!verifyTransaction(ownerPublicKey, propertyInfo, signature)) {
                throw new SecurityException("签名验证失败");
            }

            // 完善房屋信息
            propertyInfo.put("owner", ownerAddress);
            propertyInfo.put("status", "AVAILABLE"); // 可用状态
            propertyInfo.put("listedTime", System.currentTimeMillis());

            properties.put(propertyId, propertyInfo);

            result.put("status", "SUCCESS");
            result.put("message", "房屋发布成功");
            result.put("propertyId", propertyId);
        } catch (Exception e) {
            result.put("message", e.getMessage());
        }

        return result;
    }

    // 创建租赁协议
    public static Map<String, Object> createAgreement(String tenantAddress, String propertyId,
                                                      Map<String, Object> agreementTerms, String signature) {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "FAILED");

        // 验证房屋是否存在且可用
        Map<String, Object> property = properties.get(propertyId);
        if (property == null) {
            result.put("message", "房屋不存在");
            return result;
        }

        if (!"AVAILABLE".equals(property.get("status"))) {
            result.put("message", "房屋不可租");
            return result;
        }

        String ownerAddress = (String) property.get("owner");
        if (!accountKeys.containsKey(ownerAddress)) {
            result.put("message", "房东账户不存在");
            return result;
        }

        try {
            // 验证租户签名
            String tenantPublicKey = Base64.getEncoder().encodeToString(accountKeys.get(tenantAddress).getPublic().getEncoded());
            if (!verifyTransaction(tenantPublicKey, agreementTerms, signature)) {
                throw new SecurityException("租户签名验证失败");
            }

            // 生成协议ID
            String agreementId = "AGREEMENT-" + (agreementCounter++);

            // 创建租赁协议
            Map<String, Object> agreement = new HashMap<>();
            agreement.put("agreementId", agreementId);
            agreement.put("propertyId", propertyId);
            agreement.put("owner", ownerAddress);
            agreement.put("tenant", tenantAddress);
            agreement.put("startDate", agreementTerms.get("startDate"));
            agreement.put("endDate", agreementTerms.get("endDate"));
            agreement.put("rentAmount", agreementTerms.get("rentAmount"));
            agreement.put("deposit", agreementTerms.get("deposit"));
            agreement.put("status", "PENDING"); // 待房东确认
            agreement.put("createdTime", System.currentTimeMillis());

            rentalAgreements.put(agreementId, agreement);

            result.put("status", "SUCCESS");
            result.put("message", "租赁协议已创建，等待房东确认");
            result.put("agreementId", agreementId);
        } catch (Exception e) {
            result.put("message", e.getMessage());
        }

        return result;
    }

    // 房东确认租赁协议
    public static Map<String, Object> confirmAgreement(String ownerAddress, String agreementId, String signature) {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "FAILED");

        Map<String, Object> agreement = rentalAgreements.get(agreementId);
        if (agreement == null) {
            result.put("message", "租赁协议不存在");
            return result;
        }

        if (!ownerAddress.equals(agreement.get("owner"))) {
            result.put("message", "无权确认此协议");
            return result;
        }

        if (!"PENDING".equals(agreement.get("status"))) {
            result.put("message", "协议状态不允许确认");
            return result;
        }

        try {
            // 验证房东签名
            String ownerPublicKey = Base64.getEncoder().encodeToString(accountKeys.get(ownerAddress).getPublic().getEncoded());
            Map<String, Object> data = new HashMap<>();
            data.put("agreementId", agreementId);
            data.put("action", "CONFIRM");
            if (!verifyTransaction(ownerPublicKey, data, signature)) {
                throw new SecurityException("房东签名验证失败");
            }

            // 更新协议状态
            agreement.put("status", "ACTIVE");
            agreement.put("confirmedTime", System.currentTimeMillis());

            // 更新房屋状态为已出租
            String propertyId = (String) agreement.get("propertyId");
            properties.get(propertyId).put("status", "RENTED");

            result.put("status", "SUCCESS");
            result.put("message", "租赁协议已确认");
        } catch (Exception e) {
            result.put("message", e.getMessage());
        }

        return result;
    }

    // 支付租金（修复后）
    public static Map<String, Object> payRent(String tenantAddress, String agreementId,
                                              Map<String, Object> paymentInfo, String signature) {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "FAILED");

        // 1. 先生成并添加 txId 和 timestamp 到支付信息中
        long amount = (long) paymentInfo.get("amount");
        String txId = (String) paymentInfo.get("txId");
        if (txId == null || txId.isEmpty()) {
            result.put("message", "支付信息缺少 txId");
            transactionHistory.add(paymentInfo);
            return result;
        }

        // 验证交易是否已处理
        if (processedTransactions.contains(txId)) {
            result.put("message", "支付已处理（防重复支付）");
            transactionHistory.add(paymentInfo);
            return result;
        }

        Map<String, Object> agreement = rentalAgreements.get(agreementId);
        if (agreement == null) {
            result.put("message", "租赁协议不存在");
            transactionHistory.add(paymentInfo);
            return result;
        }

        if (!tenantAddress.equals(agreement.get("tenant"))) {
            result.put("message", "无权支付此租金");
            transactionHistory.add(paymentInfo);
            return result;
        }

        if (!"ACTIVE".equals(agreement.get("status"))) {
            result.put("message", "协议未激活，无法支付租金");
            transactionHistory.add(paymentInfo);
            return result;
        }

        try {
            String ownerAddress = (String) agreement.get("owner");
            long agreedRent = (long) agreement.get("rentAmount");

            if (amount != agreedRent) {
                throw new IllegalArgumentException("支付金额与协议租金不符");
            }

            // 2. 验证签名（此时 paymentInfo 已包含 txId 和 timestamp，与签名时的数据一致）
            String tenantPublicKey = Base64.getEncoder().encodeToString(accountKeys.get(tenantAddress).getPublic().getEncoded());
            if (!verifyTransaction(tenantPublicKey, paymentInfo, signature)) {
                throw new SecurityException("租户签名验证失败");
            }

            // 验证余额并转账
            synchronized (accountBalances) {
                long tenantBalance = getBalance(tenantAddress);
                if (tenantBalance < amount) {
                    throw new IllegalStateException("余额不足，当前余额: " + tenantBalance + "，需支付: " + amount);
                }

                accountBalances.put(tenantAddress, tenantBalance - amount);
                accountBalances.put(ownerAddress, getBalance(ownerAddress) + amount);
            }

            // 记录支付信息
            paymentInfo.put("status", "SUCCESS");
            paymentInfo.put("agreementId", agreementId);
            processedTransactions.add(txId);
            result.put("status", "SUCCESS");
            result.put("message", "租金支付成功");
        } catch (Exception e) {
            paymentInfo.put("status", "FAILED");
            paymentInfo.put("message", e.getMessage());
            result.put("message", e.getMessage());
        } finally {
            transactionHistory.add(paymentInfo);
        }

        return result;
    }

    // 终止租赁协议
    public static Map<String, Object> terminateAgreement(String initiatorAddress, String agreementId,
                                                         String reason, String signature) {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "FAILED");

        Map<String, Object> agreement = rentalAgreements.get(agreementId);
        if (agreement == null) {
            result.put("message", "租赁协议不存在");
            return result;
        }

        String ownerAddress = (String) agreement.get("owner");
        String tenantAddress = (String) agreement.get("tenant");
        if (!initiatorAddress.equals(ownerAddress) && !initiatorAddress.equals(tenantAddress)) {
            result.put("message", "无权终止此协议");
            return result;
        }

        if (!"ACTIVE".equals(agreement.get("status"))) {
            result.put("message", "协议状态不允许终止");
            return result;
        }

        try {
            // 验证签名
            String initiatorPublicKey = Base64.getEncoder().encodeToString(accountKeys.get(initiatorAddress).getPublic().getEncoded());
            Map<String, Object> data = new HashMap<>();
            data.put("agreementId", agreementId);
            data.put("action", "TERMINATE");
            if (!verifyTransaction(initiatorPublicKey, data, signature)) {
                throw new SecurityException("签名验证失败");
            }

            // 更新协议状态
            agreement.put("status", "TERMINATED");
            agreement.put("terminatedBy", initiatorAddress);
            agreement.put("terminateReason", reason);
            agreement.put("terminatedTime", System.currentTimeMillis());

            // 更新房屋状态为可用
            String propertyId = (String) agreement.get("propertyId");
            properties.get(propertyId).put("status", "AVAILABLE");

            result.put("status", "SUCCESS");
            result.put("message", "租赁协议已终止");
        } catch (Exception e) {
            result.put("message", e.getMessage());
        }

        return result;
    }

    // 生成交易ID
    public static String generateTxId(String from, String type, long amount) {
        String data = from + type + amount + System.currentTimeMillis() + (transactionCounter++);
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

    // 辅助方法：获取房屋信息
    public static Map<String, Object> getProperty(String propertyId) {
        return properties.getOrDefault(propertyId, new HashMap<>());
    }

    // 辅助方法：获取租赁协议
    public static Map<String, Object> getAgreement(String agreementId) {
        return rentalAgreements.getOrDefault(agreementId, new HashMap<>());
    }

    // 辅助方法：获取账户相关的租赁协议
    public static List<Map<String, Object>> getAccountAgreements(String address) {
        List<Map<String, Object>> agreements = new ArrayList<>();
        for (Map<String, Object> agreement : rentalAgreements.values()) {
            if (address.equals(agreement.get("owner")) || address.equals(agreement.get("tenant"))) {
                agreements.add(new HashMap<>(agreement));
            }
        }
        return agreements;
    }

    // 测试入口（修复了签名生成时机）
    public static void main(String[] args) {
        System.out.println("=== 启动房屋租赁合约系统 ===");

        // 1. 创建测试账户
        String landlord = "Landlord";
        String tenant = "Tenant";
        try {
            String landlordPubKey = createAccount(landlord);
            String tenantPubKey = createAccount(tenant);
            System.out.println("\n=== 账户创建成功 ===");
            System.out.println(landlord + " 公钥: " + landlordPubKey);
            System.out.println(tenant + " 公钥: " + tenantPubKey);

            // 给租户充值
            accountBalances.put(tenant, 5000L);
            System.out.println(tenant + " 初始余额: " + getBalance(tenant));
        } catch (Exception e) {
            System.err.println("账户创建失败: " + e.getMessage());
            return;
        }

        // 2. 房东发布房屋信息
        System.out.println("\n=== 房东发布房屋信息 ===");
        Map<String, Object> property = new HashMap<>();
        String propertyId = "PROP-" + System.currentTimeMillis();
        property.put("propertyId", propertyId);
        property.put("address", "北京市海淀区中关村大街1号");
        property.put("type", "公寓");
        property.put("area", 60); // 面积60平米
        property.put("description", "两室一厅，家具齐全");

        String propertySignature = signTransaction(landlord, property);
        Map<String, Object> listResult = listProperty(landlord, property, propertySignature);
        System.out.println("房屋发布结果: " + listResult.get("status"));
        System.out.println("房屋信息: " + getProperty(propertyId));

        // 3. 租户创建租赁协议
        System.out.println("\n=== 租户创建租赁协议 ===");
        Map<String, Object> agreementTerms = new HashMap<>();
        agreementTerms.put("startDate", System.currentTimeMillis() + 86400000); // 明天开始
        agreementTerms.put("endDate", System.currentTimeMillis() + 30L * 86400000); // 30天后结束
        agreementTerms.put("rentAmount", 1500L); // 月租金1500
        agreementTerms.put("deposit", 3000L); // 押金3000

        String agreementSignature = signTransaction(tenant, agreementTerms);
        Map<String, Object> createResult = createAgreement(tenant, propertyId, agreementTerms, agreementSignature);
        System.out.println("协议创建结果: " + createResult.get("status"));
        String agreementId = (String) createResult.get("agreementId");
        System.out.println("租赁协议: " + getAgreement(agreementId));

        // 4. 房东确认租赁协议
        System.out.println("\n=== 房东确认租赁协议 ===");
        Map<String, Object> confirmData = new HashMap<>();
        confirmData.put("agreementId", agreementId);
        confirmData.put("action", "CONFIRM");
        String confirmSignature = signTransaction(landlord, confirmData);
        Map<String, Object> confirmResult = confirmAgreement(landlord, agreementId, confirmSignature);
        System.out.println("协议确认结果: " + confirmResult.get("status"));
        System.out.println("更新后租赁协议: " + getAgreement(agreementId));
        System.out.println("更新后房屋状态: " + getProperty(propertyId).get("status"));

        // 5. 租户支付租金（修复：先完善支付信息再生成签名）
        System.out.println("\n=== 租户支付租金 ===");
        Map<String, Object> payment = new HashMap<>();
        payment.put("amount", 1500L);

        // 先添加 txId 和 timestamp 到支付信息中
        long amount = (long) payment.get("amount");
        String txId = generateTxId(tenant, "RENT_PAYMENT", amount);
        payment.put("txId", txId);
        payment.put("timestamp", System.currentTimeMillis());

        // 基于完整的支付信息生成签名
        String paymentSignature = signTransaction(tenant, payment);
        Map<String, Object> payResult = payRent(tenant, agreementId, payment, paymentSignature);
        System.out.println("租金支付结果: " + payResult.get("status"));
        System.out.println(tenant + " 余额: " + getBalance(tenant));
        System.out.println(landlord + " 余额: " + getBalance(landlord));

        // 6. 终止租赁协议
        System.out.println("\n=== 终止租赁协议 ===");
        Map<String, Object> terminateData = new HashMap<>();
        terminateData.put("agreementId", agreementId);
        terminateData.put("action", "TERMINATE");
        String terminateSignature = signTransaction(tenant, terminateData);
        Map<String, Object> terminateResult = terminateAgreement(tenant, agreementId, "租赁期满", terminateSignature);
        System.out.println("协议终止结果: " + terminateResult.get("status"));
        System.out.println("终止后租赁协议: " + getAgreement(agreementId));
        System.out.println("终止后房屋状态: " + getProperty(propertyId).get("status"));
    }
}