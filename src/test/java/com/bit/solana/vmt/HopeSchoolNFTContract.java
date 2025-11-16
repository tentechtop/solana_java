package com.bit.solana.vmt;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Base64;

public class HopeSchoolNFTContract {
    // 核心存储结构
    private static final Map<String, KeyPair> accountKeys = new ConcurrentHashMap<>();
    private static final Map<String, Map<String, Object>> nfts = new ConcurrentHashMap<>(); // NFT元数据: tokenId -> 信息
    private static final Map<String, String> nftOwners = new ConcurrentHashMap<>(); // tokenId -> 所有者地址
    private static final Map<String, List<String>> ownerNFTs = new ConcurrentHashMap<>(); // 所有者地址 -> 拥有的tokenId列表
    private static final List<Map<String, Object>> transactions = new ArrayList<>(); // 交易记录
    private static final Set<String> processedTxIds = new HashSet<>();

    // 系统参数
    private static final String CURVE_NAME = "secp256r1";
    private static final String SIGNATURE_ALGORITHM = "SHA256withECDSA";
    private static long tokenCounter = 0; // NFT计数器
    private static final String CONTRACT_NAME = "希望小学纪念NFT";

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
            ownerNFTs.putIfAbsent(address, new ArrayList<>());
            return Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
        } catch (Exception e) {
            throw new RuntimeException("账户创建失败", e);
        }
    }

    // 生成NFT
    public static Map<String, Object> mintNFT(Map<String, Object> transaction, String signature) {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "FAILED");

        // 提取交易字段
        String minter = (String) transaction.get("minter");
        String name = (String) transaction.get("name");
        String description = (String) transaction.get("description");
        String txId = (String) transaction.get("txId");
        Long timestamp = (Long) transaction.get("timestamp");

        // 验证必要字段
        if (minter == null || name == null || description == null || txId == null || timestamp == null) {
            result.put("message", "交易数据不完整");
            return result;
        }

        // 验证账户存在
        if (!accountKeys.containsKey(minter)) {
            result.put("message", "创作者账户不存在");
            return result;
        }

        // 防重放
        if (processedTxIds.contains(txId)) {
            result.put("message", "该NFT已铸造");
            return result;
        }

        try {
            // 验证签名（使用交易中的原始时间戳）
            String minterPubKey = Base64.getEncoder().encodeToString(
                    accountKeys.get(minter).getPublic().getEncoded());
            if (!verifyTransaction(minterPubKey, transaction, signature)) {
                throw new SecurityException("签名验证失败");
            }

            // 生成唯一tokenId
            String tokenId = generateTokenId(minter, timestamp);

            // 存储NFT信息
            Map<String, Object> nftMeta = new HashMap<>();
            nftMeta.put("tokenId", tokenId);
            nftMeta.put("name", name);
            nftMeta.put("description", description);
            nftMeta.put("minter", minter);
            nftMeta.put("mintTime", timestamp);
            nftMeta.put("contract", CONTRACT_NAME);

            // 更新所有权信息
            synchronized (HopeSchoolNFTContract.class) {
                nfts.put(tokenId, nftMeta);
                nftOwners.put(tokenId, minter);
                ownerNFTs.get(minter).add(tokenId);

                // 记录交易
                Map<String, Object> txRecord = new HashMap<>(transaction);
                txRecord.put("type", "MINT");
                txRecord.put("status", "SUCCESS");
                transactions.add(txRecord);
                processedTxIds.add(txId);
            }

            result.put("status", "SUCCESS");
            result.put("message", "NFT铸造成功");
            result.put("tokenId", tokenId);
            result.put("nft", nftMeta);
        } catch (Exception e) {
            result.put("message", e.getMessage());
        }

        return result;
    }

    // 转移NFT所有权
    public static Map<String, Object> transferNFT(Map<String, Object> transaction, String signature) {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "FAILED");

        // 提取交易字段
        String from = (String) transaction.get("from");
        String to = (String) transaction.get("to");
        String tokenId = (String) transaction.get("tokenId");
        String txId = (String) transaction.get("txId");
        Long timestamp = (Long) transaction.get("timestamp");

        // 验证必要字段
        if (from == null || to == null || tokenId == null || txId == null || timestamp == null) {
            result.put("message", "交易数据不完整");
            return result;
        }

        // 验证账户存在
        if (!accountKeys.containsKey(from)) {
            result.put("message", "转出账户不存在");
            return result;
        }
        if (!accountKeys.containsKey(to)) {
            result.put("message", "转入账户不存在");
            return result;
        }

        // 验证NFT存在
        if (!nfts.containsKey(tokenId)) {
            result.put("message", "NFT不存在");
            return result;
        }

        // 验证所有权
        if (!from.equals(nftOwners.get(tokenId))) {
            result.put("message", "您不是该NFT的所有者");
            return result;
        }

        // 防重放
        if (processedTxIds.contains(txId)) {
            result.put("message", "该交易已处理");
            return result;
        }

        try {
            // 验证签名（使用交易中的原始时间戳）
            String fromPubKey = Base64.getEncoder().encodeToString(
                    accountKeys.get(from).getPublic().getEncoded());
            if (!verifyTransaction(fromPubKey, transaction, signature)) {
                throw new SecurityException("签名验证失败");
            }

            // 更新所有权信息
            synchronized (HopeSchoolNFTContract.class) {
                // 从原所有者移除
                ownerNFTs.get(from).remove(tokenId);
                // 添加到新所有者
                ownerNFTs.get(to).add(tokenId);
                // 更新所有者映射
                nftOwners.put(tokenId, to);

                // 记录交易
                Map<String, Object> txRecord = new HashMap<>(transaction);
                txRecord.put("type", "TRANSFER");
                txRecord.put("status", "SUCCESS");
                transactions.add(txRecord);
                processedTxIds.add(txId);
            }

            result.put("status", "SUCCESS");
            result.put("message", "NFT转移成功");
            result.put("tokenId", tokenId);
            result.put("newOwner", to);
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

    // 生成TokenId
    private static String generateTokenId(String minter, long timestamp) {
        String data = minter + "NFT" + timestamp + (tokenCounter++);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash).substring(0, 20);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("生成TokenId失败", e);
        }
    }

    // 生成交易ID
    public static String generateTxId(String address, String action) {
        String data = address + action + System.currentTimeMillis() + UUID.randomUUID().toString();
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

    // 查询NFT信息
    public static Map<String, Object> getNFTInfo(String tokenId) {
        Map<String, Object> info = new HashMap<>(nfts.getOrDefault(tokenId, new HashMap<>()));
        info.put("owner", nftOwners.getOrDefault(tokenId, "未知"));
        return info;
    }

    // 查询账户拥有的NFT
    public static List<Map<String, Object>> getAccountNFTs(String address) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (!ownerNFTs.containsKey(address)) {
            return result;
        }
        for (String tokenId : ownerNFTs.get(address)) {
            result.add(getNFTInfo(tokenId));
        }
        return result;
    }

    // 获取交易记录
    public static List<Map<String, Object>> getTransactionHistory() {
        return new ArrayList<>(transactions);
    }

    // 测试入口
    public static void main(String[] args) {
        System.out.println("=== 启动希望小学纪念NFT合约 ===");

        // 1. 创建测试账户
        String alice = "Alice";
        String bob = "Bob";
        try {
            String alicePubKey = createAccount(alice);
            String bobPubKey = createAccount(bob);
            System.out.println("\n=== 账户创建成功 ===");
            System.out.println(alice + " 公钥: " + alicePubKey.substring(0, 30) + "...");
            System.out.println(bob + " 公钥: " + bobPubKey.substring(0, 30) + "...");
        } catch (Exception e) {
            System.err.println("账户创建失败: " + e.getMessage());
            return;
        }

        // 2. Alice铸造NFT
        System.out.println("\n=== Alice 铸造NFT ===");
        String mintTxId = generateTxId(alice, "MINT");
        long mintTime = System.currentTimeMillis();
        Map<String, Object> mintTx = new HashMap<>();
        mintTx.put("minter", alice);
        mintTx.put("name", "希望小学建设贡献者");
        mintTx.put("description", "纪念为希望小学建设做出贡献的爱心人士");
        mintTx.put("txId", mintTxId);
        mintTx.put("timestamp", mintTime); // 签名用的时间戳（取自交易）

        String mintSignature = signTransaction(alice, mintTx);
        Map<String, Object> mintResult = mintNFT(mintTx, mintSignature);
        System.out.println("铸造结果: " + mintResult.get("message"));
        String tokenId = (String) mintResult.get("tokenId");
        System.out.println("NFT TokenId: " + tokenId);

        // 3. 查看Alice的NFT
        System.out.println("\n=== Alice 拥有的NFT ===");
        List<Map<String, Object>> aliceNFTs = getAccountNFTs(alice);
        for (Map<String, Object> nft : aliceNFTs) {
            System.out.println("名称: " + nft.get("name") + ", 描述: " + nft.get("description"));
        }

        // 4. Alice转移NFT给Bob
        System.out.println("\n=== Alice 转移NFT给Bob ===");
        String transferTxId = generateTxId(alice, "TRANSFER");
        long transferTime = System.currentTimeMillis();
        Map<String, Object> transferTx = new HashMap<>();
        transferTx.put("from", alice);
        transferTx.put("to", bob);
        transferTx.put("tokenId", tokenId);
        transferTx.put("txId", transferTxId);
        transferTx.put("timestamp", transferTime); // 签名用的时间戳（取自交易）

        String transferSignature = signTransaction(alice, transferTx);
        Map<String, Object> transferResult = transferNFT(transferTx, transferSignature);
        System.out.println("转移结果: " + transferResult.get("message"));

        // 5. 查看Bob的NFT
        System.out.println("\n=== Bob 拥有的NFT ===");
        List<Map<String, Object>> bobNFTs = getAccountNFTs(bob);
        for (Map<String, Object> nft : bobNFTs) {
            System.out.println("名称: " + nft.get("name") + ", 所有者: " + nft.get("owner"));
        }

        // 6. 查看交易记录
        System.out.println("\n=== 交易记录 ===");
        List<Map<String, Object>> txHistory = getTransactionHistory();
        for (Map<String, Object> tx : txHistory) {
            System.out.println(tx.get("type") + " - " + tx.get("txId") + " - 时间: " +
                    new Date((Long) tx.get("timestamp")));
        }
    }
}