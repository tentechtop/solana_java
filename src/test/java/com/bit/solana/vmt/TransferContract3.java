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
public class TransferContract3 {
    // 状态变更Map结构（所有方法统一返回此格式）
/*    Map<String, Object> stateChange = new HashMap<>();
stateChange.put("operation", "INIT_ACCOUNT"); // 操作类型：INIT_ACCOUNT/TRANSFER/QUERY（仅变更状态的操作需写入区块）
stateChange.put("txId", txId); // 交易ID（唯一标识，避免重复）
stateChange.put("timestamp", System.currentTimeMillis()); // 时间戳
stateChange.put("beforeState", beforeMap); // 操作前状态快照（仅变更的字段）
stateChange.put("afterState", afterMap); // 操作后状态快照（仅变更的字段）
stateChange.put("success", true); // 操作结果
stateChange.put("errorMsg", null); // 错误信息（失败时非空）*/

    // 保存链提供的查询接口实例（类型为Object，避免依赖具体类）
    private Object database;

    // 反射缓存：方法名 -> Method对象
    private final Map<String, Method> dbMethods = new HashMap<>();


    //合约要提供注入方法  注入查询和合约数据库

    // 构造方法：接收链传入的查询接口实例（链会通过反射调用此构造方法）
    public TransferContract3(Object database) {
        this.database = database;
        // 初始化反射方法（在构造时缓存，避免每次调用都反射）
        initReflectionMethods();
    }


    // 注册方法
    private void initReflectionMethods() {
        try {
            // 获取链查询接口的Class对象
            Class<?> databaseClass = database.getClass();
            // 2. 反射加载TableEnum枚举类（注入枚举类）
            Class<?> tableEnumClass = Class.forName("com.bit.solana.database.rocksDb.TableEnum");

            Method insertMethod = databaseClass.getMethod(
                    "insert",
                    tableEnumClass,  // 参数1：TableEnum枚举类型
                    byte[].class,    // 参数2：key
                    byte[].class     // 参数3：value
            );
            dbMethods.put("insert", insertMethod);

            Method isExistMethod = databaseClass.getMethod(
                    "isExist",
                    tableEnumClass,
                    byte[].class
            );
            dbMethods.put("isExist", isExistMethod);

            Method deleteMethod = databaseClass.getMethod(
                    "delete",
                    tableEnumClass,
                    byte[].class
            );
            dbMethods.put("delete", deleteMethod);

            Method countMethod = databaseClass.getMethod(
                    "count",
                    tableEnumClass
            );
            dbMethods.put("count", countMethod);


            Method batchInsertMethod = databaseClass.getMethod(
                    "batchInsert",
                    tableEnumClass,
                    byte[][].class,
                    byte[][].class
            );
            dbMethods.put("batchInsert", batchInsertMethod);

            // 3.6 batchDelete(TableEnum table, byte[][] keys)
            Method batchDeleteMethod = databaseClass.getMethod(
                    "batchDelete",
                    tableEnumClass,
                    byte[][].class
            );
            dbMethods.put("batchDelete", batchDeleteMethod);

            // 3.7 batchGet(TableEnum table, byte[][] keys)
            Method batchGetMethod = databaseClass.getMethod(
                    "batchGet",
                    tableEnumClass,
                    byte[][].class
            );
            dbMethods.put("batchGet", batchGetMethod);

        } catch (Exception e) {
            // 若方法不存在，抛出异常终止合约（链会处理此错误）
            throw new RuntimeException("初始化查询方法失败", e);
        }
    }


    /**
     * 无参数测试方法：向ACCOUNT表插入一条条测试数据并查询验证
     */
    public void testInsertAndQuery() {
        try {
            // 获取表实例（一行搞定）
            Object accountTable = getTable("ACCOUNT");
            // 准备测试数据
            String testAddress = "TEST_INSERT_QUERY_001";
            byte[] key = testAddress.getBytes();
            long testBalance = 10000L;
            byte[] value = String.valueOf(testBalance).getBytes();

            // 插入数据（直接调用封装方法）
            dbInsert(accountTable, key, value);
            System.out.println("已插入测试数据：地址=" + testAddress + ", 余额=" + testBalance);

            // 验证存在性
            boolean exists = dbIsExist(accountTable, key);
            System.out.println("数据存在性验证：" + (exists ? "存在" : "不存在"));

            // 批量查询
            byte[][] keys = new byte[][]{key};
            byte[][] results = dbBatchGet(accountTable, keys);

            // 解析结果
            if (results != null && results.length > 0 && results[0] != null) {
                String storedBalance = new String(results[0]);
                System.out.println("查询到的余额：" + storedBalance);
                if (storedBalance.equals(String.valueOf(testBalance))) {
                    System.out.println("插入与查询数据一致，测试成功");
                } else {
                    System.out.println("插入与查询数据不一致，测试失败");
                }
            } else {
                System.out.println("未查询到数据，测试失败");
            }

        } catch (Exception e) {
            System.err.println("测试执行失败：" + e.getMessage());
            e.printStackTrace();
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

    /**
     * 封装数据库插入操作
     */
    private void dbInsert(Object table, byte[] key, byte[] value) {
        try {
            Method method = dbMethods.get("insert");
            if (method == null) throw new RuntimeException("insert方法未初始化");
            method.invoke(database, table, key, value);
        } catch (Exception e) {
            throw new RuntimeException("插入数据失败: " + e.getMessage(), e);
        }
    }

    /**
     * 封装数据库存在性检查
     */
    private boolean dbIsExist(Object table, byte[] key) {
        try {
            Method method = dbMethods.get("isExist");
            if (method == null) throw new RuntimeException("isExist方法未初始化");
            return (boolean) method.invoke(database, table, key);
        } catch (Exception e) {
            throw new RuntimeException("检查数据存在性失败: " + e.getMessage(), e);
        }
    }

    /**
     * 封装批量查询操作
     */
    private byte[][] dbBatchGet(Object table, byte[][] keys) {
        try {
            Method method = dbMethods.get("batchGet");
            if (method == null) throw new RuntimeException("batchGet方法未初始化");
            return (byte[][]) method.invoke(database, table, keys);
        } catch (Exception e) {
            throw new RuntimeException("批量查询失败: " + e.getMessage(), e);
        }
    }

    /**
     * 封装批量插入操作
     */
    private void dbBatchInsert(Object table, byte[][] keys, byte[][] values) {
        try {
            Method method = dbMethods.get("batchInsert");
            if (method == null) throw new RuntimeException("batchInsert方法未初始化");
            method.invoke(database, table, keys, values);
        } catch (Exception e) {
            throw new RuntimeException("批量插入失败: " + e.getMessage(), e);
        }
    }

    /**
     * 封装计数操作
     */
    private int dbCount(Object table) {
        try {
            Method method = dbMethods.get("count");
            if (method == null) throw new RuntimeException("count方法未初始化");
            return (int) method.invoke(database, table);
        } catch (Exception e) {
            throw new RuntimeException("计数失败: " + e.getMessage(), e);
        }
    }

    /**
     * 获取指定表的枚举实例
     */
    private Object getTable(String tableName) {
        try {
            Class<?> tableEnumClass = Class.forName("com.bit.solana.database.rocksDb.TableEnum");
            return Enum.valueOf((Class<Enum>) tableEnumClass, tableName);
        } catch (Exception e) {
            throw new RuntimeException("获取表[" + tableName + "]失败: " + e.getMessage(), e);
        }
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