package com.bit.solana.vmt;

import java.lang.reflect.Method;
import java.security.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单个类实现转账合约及签名功能
 * 使用javac编译时 禁止使用自定类 内部类 或者其他别的类 只能使用java的类  javac -d . TransferContract.java
 */
public class TransferContract {
    // 保存链提供的查询接口实例（类型为Object，避免依赖具体类）
    private Object database;

    // 保存链提供的查询接口实例（类型为Object，避免依赖具体类）
    private Object chain;

    // 缓存反射方法

    //合约要提供注入方法  注入查询和合约数据库

    // 构造方法：接收链传入的查询接口实例（链会通过反射调用此构造方法）
    public TransferContract(Object database,Object chain) {
        this.database = database;
        this.chain = chain;
        // 初始化反射方法（在构造时缓存，避免每次调用都反射）
        initReflectionMethods();
    }


    // 初始化反射方法：获取链查询接口的方法（如getState、getBlockTimestamp）
    private void initReflectionMethods() {
        try {
            // 获取链查询接口的Class对象
            Class<?> queryClass = database.getClass();
            //缓存数据库 和 区块链 反射方法

        } catch (Exception e) {
            // 若方法不存在，抛出异常终止合约（链会处理此错误）
            throw new RuntimeException("初始化查询方法失败", e);
        }
    }

    // 账户余额存储（地址 -> 余额）
    private static final Map<String, Long> accountBalances = new ConcurrentHashMap<>();

    // 交易记录：用Map存储每条交易的详情
    private static final List<Map<String, Object>> transactionHistory = new ArrayList<>();

    // 交易计数器
    private static long transactionCount = 0;

    // 存储账户的密钥对（地址 -> 密钥对）
    private static final Map<String, KeyPair> accountKeyPairs = new ConcurrentHashMap<>();


    /**
     * 初始化账户（包含密钥对生成，用于签名）
     */
    public static void initAccount(String address, long initialBalance) {
        if (initialBalance < 0) {
            throw new IllegalArgumentException("初始余额不能为负数");
        }
        accountBalances.putIfAbsent(address, initialBalance);

        // 为账户生成密钥对（仅当不存在时）
        if (!accountKeyPairs.containsKey(address)) {
            try {
                KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
                keyGen.initialize(2048); // 使用2048位RSA密钥
                KeyPair keyPair = keyGen.generateKeyPair();
                accountKeyPairs.put(address, keyPair);
                System.out.println("生成账户密钥对: " + address);
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException("密钥生成失败", e);
            }
        }
        System.out.println("初始化账户: " + address + ", 初始余额: " + initialBalance);
    }

    /**
     * 获取账户公钥（用于验证签名）
     */
    public static PublicKey getPublicKey(String address) {
        KeyPair keyPair = accountKeyPairs.get(address);
        if (keyPair == null) {
            throw new IllegalArgumentException("账户未初始化: " + address);
        }
        return keyPair.getPublic();
    }

    /**
     * 对交易信息进行签名
     * @param address 签名账户地址（需已初始化）
     * @param data 待签名的数据（如交易详情字符串）
     * @return 签名字节数组
     */
    public static byte[] signData(String address, String data) {
        KeyPair keyPair = accountKeyPairs.get(address);
        if (keyPair == null) {
            throw new IllegalArgumentException("账户未初始化: " + address);
        }
        try {
            // 使用私钥签名
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(keyPair.getPrivate());
            signature.update(data.getBytes());
            return signature.sign();
        } catch (Exception e) {
            throw new RuntimeException("签名失败", e);
        }
    }

    /**
     * 验证签名
     * @param publicKey 签名者公钥
     * @param data 原始数据
     * @param signature 待验证的签名
     * @return 验证是否通过
     */
    public static boolean verifySignature(PublicKey publicKey, String data, byte[] signature) {
        try {
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(publicKey);
            verifier.update(data.getBytes());
            return verifier.verify(signature);
        } catch (Exception e) {
            throw new RuntimeException("签名验证失败", e);
        }
    }

    /**
     * 查询账户余额
     */
    public static long getBalance(String address) {
        return accountBalances.getOrDefault(address, 0L);
    }

    /**
     * 带签名验证的转账核心逻辑
     * @param from 转出地址
     * @param to 转入地址
     * @param amount 金额
     * @param signature 转出者对交易的签名
     * @return 交易详情Map
     */
    public static Map<String, Object> transferWithSignature(String from, String to, long amount, byte[] signature) {
        String txId = "TX" + (++transactionCount);
        long timestamp = System.currentTimeMillis();
        boolean success = false;
        String errorMsg = null;

        try {
            // 1. 参数校验
            if (amount <= 0) {
                throw new IllegalArgumentException("转账金额必须大于0");
            }
            if (from == null || from.isEmpty() || to == null || to.isEmpty()) {
                throw new IllegalArgumentException("地址不能为空");
            }
            if (from.equals(to)) {
                throw new IllegalArgumentException("转出和转入地址不能相同");
            }

            // 2. 构建交易数据并验证签名
            String transactionData = String.format("from=%s&to=%s&amount=%d&txId=%s", from, to, amount, txId);
            PublicKey fromPublicKey = getPublicKey(from);
            if (!verifySignature(fromPublicKey, transactionData, signature)) {
                throw new SecurityException("签名验证失败，交易无效");
            }

            // 3. 余额校验与转账操作
            synchronized (accountBalances) {
                long fromBalance = getBalance(from);
                if (fromBalance < amount) {
                    throw new IllegalStateException("余额不足，当前余额: " + fromBalance + ", 需转账: " + amount);
                }

                // 更新余额
                accountBalances.put(from, fromBalance - amount);
                accountBalances.put(to, getBalance(to) + amount);
                success = true;
                System.out.println("转账成功: " + from + " -> " + to + ", 金额: " + amount);
            }
        } catch (Exception e) {
            errorMsg = e.getMessage();
            System.err.println("转账失败: " + errorMsg);
        } finally {
            // 存储交易详情
            Map<String, Object> tx = new HashMap<>();
            tx.put("txId", txId);
            tx.put("from", from);
            tx.put("to", to);
            tx.put("amount", amount);
            tx.put("timestamp", timestamp);
            tx.put("success", success);
            tx.put("errorMsg", errorMsg);
            transactionHistory.add(tx);
        }

        return transactionHistory.get(transactionHistory.size() - 1);
    }

    /**
     * 查询账户交易历史
     */
    public static List<Map<String, Object>> getTransactionHistory(String address) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> tx : transactionHistory) {
            if (address == null
                    || tx.get("from").equals(address)
                    || tx.get("to").equals(address)) {
                result.add(tx);
            }
        }
        return result;
    }


    // 测试入口
    public static void main(String[] args) {
        System.out.println("===== 启动带签名功能的转账合约 =====");

        // 初始化账户（自动生成密钥对）
        initAccount("Alice", 1000);
        initAccount("Bob", 500);

        // 构造交易并签名
        System.out.println("\n===== 执行带签名的转账 =====");
        String from = "Alice";
        String to = "Bob";
        long amount = 300;
        String txId = "TX" + (transactionCount + 1); // 预先生成txId用于签名
        String transactionData = String.format("from=%s&to=%s&amount=%d&txId=%s", from, to, amount, txId);

        // Alice对交易数据签名
        byte[] signature = signData(from, transactionData);
        System.out.println("交易签名完成");

        // 执行带签名验证的转账
        transferWithSignature(from, to, amount, signature);

        // 测试无效签名（篡改交易数据）
        System.out.println("\n===== 测试无效签名 =====");
        String fakeData = String.format("from=%s&to=%s&amount=%d&txId=%s", from, to, 500, txId); // 篡改金额
        byte[] fakeSignature = signData(from, fakeData);
        transferWithSignature(from, to, amount, fakeSignature); // 签名与实际交易数据不匹配，验证失败

        // 查看余额
        System.out.println("\n===== 账户余额 =====");
        System.out.println("Alice: " + getBalance("Alice"));
        System.out.println("Bob: " + getBalance("Bob"));

        // 查看交易历史
        System.out.println("\n===== 交易历史 =====");
        for (Map<String, Object> tx : getTransactionHistory(null)) {
            System.out.printf(
                    "交易ID: %s, 状态: %s, 详情: %s->%s, 金额: %d%n",
                    tx.get("txId"),
                    tx.get("success").equals(true) ? "成功" : "失败（" + tx.get("errorMsg") + "）",
                    tx.get("from"),
                    tx.get("to"),
                    tx.get("amount")
            );
        }
    }
}