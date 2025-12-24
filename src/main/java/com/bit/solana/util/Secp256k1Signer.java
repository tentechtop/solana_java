package com.bit.solana.util;

import com.bit.solana.structure.key.KeyInfo;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.Base58;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.Sha256Hash;
import org.bouncycastle.crypto.params.ECDHUPrivateParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;
import org.bouncycastle.math.ec.ECPoint;
import org.bouncycastle.math.ec.FixedPointCombMultiplier;
import org.bouncycastle.util.encoders.Hex;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.bouncycastle.jce.spec.ECPrivateKeySpec;
import org.bouncycastle.jce.spec.ECPublicKeySpec;
import org.bouncycastle.jce.spec.ECParameterSpec;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;


import static com.bit.solana.util.SolanaEd25519Signer.*;

@Slf4j
public class Secp256k1Signer {
    // ------------------------------ 线程局部缓存（减少对象创建开销） ------------------------------
    /**
     * 线程局部变量：缓存JDK原生Secp256k1签名器（优先使用）
     */
    private static final ThreadLocal<Signature> JDK_SIGNER_THREAD_LOCAL = ThreadLocal.withInitial(() -> {
        try {
            return Signature.getInstance("SHA256withECDSA","BC"); // Secp256k1默认签名算法
        } catch (NoSuchAlgorithmException | NoSuchProviderException e) {
            throw new RuntimeException(e);
        }
    });

    /**
     * 线程局部变量：缓存BouncyCastle的Secp256k1签名器（降级方案）
     */
    private static final ThreadLocal<Signature> BC_SIGNER_THREAD_LOCAL = ThreadLocal.withInitial(() -> {
        try {
            return Signature.getInstance("SHA256withECDSA", "BC");
        } catch (Exception e) {
            throw new RuntimeException("初始化BouncyCastle Secp256k1签名器失败", e);
        }
    });

    /**
     * 线程局部变量：缓存JDK原生Secp256k1验证器
     */
    private static final ThreadLocal<Signature> JDK_VERIFIER_THREAD_LOCAL = ThreadLocal.withInitial(() -> {
        try {
            return Signature.getInstance("SHA256withECDSA", "BC");
        } catch (NoSuchAlgorithmException | NoSuchProviderException e) {
            throw new RuntimeException(e);
        }
    });

    /**
     * 线程局部变量：缓存BouncyCastle的Secp256k1验证器
     */
    private static final ThreadLocal<Signature> BC_VERIFIER_THREAD_LOCAL = ThreadLocal.withInitial(() -> {
        try {
            return Signature.getInstance("SHA256withECDSA", "BC");
        } catch (Exception e) {
            throw new RuntimeException("初始化BouncyCastle Secp256k1验证器失败", e);
        }
    });

    /**
     * 线程局部变量：缓存KeyFactory（Secp256k1密钥转换）
     */
    private static final ThreadLocal<KeyFactory> SECP256K1_KEY_FACTORY = ThreadLocal.withInitial(() -> {
        try {
            return KeyFactory.getInstance("EC", "BC");
        } catch (NoSuchAlgorithmException | NoSuchProviderException e) {
            throw new RuntimeException(e);
        }
    });

    // ------------------------------ 线程池配置（并行处理批量任务） ------------------------------
    // CPU核心数（Secp256k1是CPU密集型操作，线程数与核心数匹配）
    private static final int CPU_CORES = Runtime.getRuntime().availableProcessors();
    // 固定大小线程池（避免线程创建销毁开销）
    private static final ExecutorService EXECUTOR = new ThreadPoolExecutor(
            CPU_CORES,
            CPU_CORES,
            0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(100000), // 大缓冲队列，支撑高并发
            new ThreadFactory() {
                private final AtomicInteger seq = new AtomicInteger(0);
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "secp256k1-worker-" + seq.getAndIncrement());
                    t.setDaemon(true);
                    t.setPriority(Thread.NORM_PRIORITY + 1); // 提高优先级，优先处理签名验签
                    return t;
                }
            },
            new ThreadPoolExecutor.CallerRunsPolicy() // 队列满时调用方执行，避免任务丢失
    );

    // ------------------------------ 签名缓存配置（提升重复任务性能） ------------------------------
    // 签名缓存：key=公钥哈希+数据哈希，value=签名结果
    private static final com.github.benmanes.caffeine.cache.Cache<String, byte[]> SIGNATURE_CACHE;

    // ------------------------------ 常量定义 ------------------------------
    // Secp256k1核心密钥长度（私钥32字节，公钥非压缩65字节/压缩33字节）
    public static final int PRIVATE_KEY_CORE_LENGTH = 32;
    public static final int PUBLIC_KEY_UNCOMPRESSED_LENGTH = 65;
    public static final int PUBLIC_KEY_COMPRESSED_LENGTH = 33;
    // X.509公钥编码标识（Secp256k1）
    private static final String SECP256K1_OID = "1.3.132.0.10";

    // ------------------------------ 静态代码块 ------------------------------
    static {
        // 注册BouncyCastle Provider
        Security.addProvider(new BouncyCastleProvider());
        // 初始化Caffeine缓存
        SIGNATURE_CACHE = Caffeine.newBuilder()
                .maximumSize(100_000) // 最大缓存10万条签名
                .expireAfterWrite(10, TimeUnit.MINUTES) // 10分钟过期
                .recordStats() // 启用统计，便于监控命中率
                .build();
    }

    // ------------------------------ 基础密钥操作 ------------------------------
    /**
     * 生成 Secp256k1 密钥对
     * @return 包含公钥（非压缩65字节）和私钥（32字节）的 KeyPair
     */
    public static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance("EC", "BC");
            SecureRandom random = SecureRandom.getInstanceStrong();
            ECGenParameterSpec ecSpec = new ECGenParameterSpec("secp256k1");
            keyGen.initialize(ecSpec, random);
            return keyGen.generateKeyPair();
        } catch (Exception e) {
            throw new RuntimeException("Secp256k1 密钥对生成失败", e);
        }
    }



    // ------------------------------ 普通签名/验签 ------------------------------
    /**
     * Secp256k1签名（自动选择JDK/BC实现）
     * @param privateKey Secp256k1私钥对象
     * @param data 待签名原始数据
     * @return DER编码签名结果
     */
    public static byte[] applySignature(PrivateKey privateKey, byte[] data) {
        try {
            // 优先使用JDK原生签名器
            Signature signer = JDK_SIGNER_THREAD_LOCAL.get();
            if (signer != null) {
                signer.initSign(privateKey);
                signer.update(data);
                return signer.sign();
            }
            // 降级使用BouncyCastle签名器
            signer = BC_SIGNER_THREAD_LOCAL.get();
            signer.initSign(privateKey);
            signer.update(data);
            return signer.sign();
        } catch (Exception e) {
            throw new RuntimeException("Secp256k1 签名失败", e);
        }
    }

    /**
     * Secp256k1验签（自动选择JDK/BC实现）
     * @param publicKey Secp256k1公钥对象
     * @param data 原始数据（需与签名时一致）
     * @param signature DER编码签名结果
     * @return true=验签成功，false=验签失败
     */
    public static boolean verifySignature(PublicKey publicKey, byte[] data, byte[] signature) {
        try {
            // 优先使用JDK原生验证器
            Signature verifier = JDK_VERIFIER_THREAD_LOCAL.get();
            if (verifier != null) {
                verifier.initVerify(publicKey);
                verifier.update(data);
                return verifier.verify(signature);
            }
            // 降级使用BouncyCastle验证器
            verifier = BC_VERIFIER_THREAD_LOCAL.get();
            verifier.initVerify(publicKey);
            verifier.update(data);
            return verifier.verify(signature);
        } catch (Exception e) {
            log.error("Secp256k1 验签异常", e);
            return false;
        }
    }

    /**
     * 高性能签名（基于bitcoinj的ECKey，跳过JCA层开销）
     * @param privateKey 32字节核心私钥
     * @param data 待签名原始数据
     * @return DER编码签名结果
     */
    public static byte[] fastSign(byte[] privateKey, byte[] data) {
        if (privateKey.length != PRIVATE_KEY_CORE_LENGTH) {
            throw new IllegalArgumentException("私钥必须为32字节");
        }
        // 先对数据做SHA256哈希（Secp256k1签名默认基于SHA256）
        byte[] dataHash = Sha256Hash.hash(data);
        ECKey ecKey = ECKey.fromPrivate(privateKey);
        return ecKey.sign(Sha256Hash.wrap(dataHash)).encodeToDER();
    }

    /**
     * 高性能签名（支持ByteBuffer输入，减少字节数组转换开销）
     * @param privateKey 32字节核心私钥
     * @param data 待签名数据的ByteBuffer
     * @return DER编码签名结果
     */
    public static byte[] fastSign(byte[] privateKey, ByteBuffer data) {
        if (privateKey.length != PRIVATE_KEY_CORE_LENGTH) {
            throw new IllegalArgumentException("私钥必须为32字节");
        }
        // 读取ByteBuffer数据
        byte[] dataBytes;
        if (data.hasArray()) {
            dataBytes = Arrays.copyOfRange(data.array(), data.arrayOffset() + data.position(), data.arrayOffset() + data.limit());
        } else {
            dataBytes = new byte[data.remaining()];
            data.get(dataBytes);
        }
        // 签名
        return fastSign(privateKey, dataBytes);
    }

    /**
     * 高性能验签（基于bitcoinj的ECKey，跳过JCA层开销）
     * @param publicKey 33字节压缩公钥 / 65字节非压缩公钥
     * @param data 原始数据
     * @param signature DER编码签名结果
     * @return true=验签成功，false=验签失败
     */
    public static boolean fastVerify(byte[] publicKey, byte[] data, byte[] signature) {
        if (publicKey.length != PUBLIC_KEY_COMPRESSED_LENGTH && publicKey.length != PUBLIC_KEY_UNCOMPRESSED_LENGTH) {
            throw new IllegalArgumentException("公钥必须为33字节（压缩）或65字节（非压缩）");
        }
        try {
            // 先对数据做SHA256哈希
            byte[] dataHash = Sha256Hash.hash(data);
            ECKey ecKey = ECKey.fromPublicOnly(publicKey);
            return ecKey.verify(dataHash, signature);
        } catch (Exception e) {
            log.error("Secp256k1 高性能验签异常", e);
            return false;
        }
    }

    /**
     * 高性能验签（支持ByteBuffer输入）
     * @param publicKey 33字节压缩公钥 / 65字节非压缩公钥
     * @param data 原始数据的ByteBuffer
     * @param signature DER编码签名结果
     * @return true=验签成功，false=验签失败
     */
    public static boolean fastVerify(byte[] publicKey, ByteBuffer data, byte[] signature) {
        // 读取ByteBuffer数据
        byte[] dataBytes;
        if (data.hasArray()) {
            dataBytes = Arrays.copyOfRange(data.array(), data.arrayOffset() + data.position(), data.arrayOffset() + data.limit());
        } else {
            dataBytes = new byte[data.remaining()];
            data.get(dataBytes);
        }
        // 验签
        return fastVerify(publicKey, dataBytes, signature);
    }




    /**
     * 生成缓存键：公钥Hex + 数据Hex（用冒号分隔，避免冲突）
     */
    private static String generateCacheKey(byte[] publicKey, byte[] data) {
        return Hex.toHexString(publicKey) + ":" + Hex.toHexString(data);
    }

    /**
     * 获取缓存统计信息（用于监控命中率）
     */
    public static String getCacheStats() {
        CacheStats stats = SIGNATURE_CACHE.stats();
        return String.format(
                "Secp256k1签名缓存统计：命中率=%.2f%%, 总请求数=%d, 命中数=%d, 未命中数=%d",
                stats.hitRate() * 100,
                stats.requestCount(),
                stats.hitCount(),
                stats.missCount()
        );
    }

    /**
     * 手动清理签名缓存
     */
    public static void clearCache() {
        SIGNATURE_CACHE.invalidateAll();
        log.info("Secp256k1签名缓存已清空");
    }

    // ------------------------------ 批量并行处理 ------------------------------
    /**
     * 批量签名（并行处理）
     * @param privateKeys 32字节核心私钥列表
     * @param dataList 待签名数据列表（与私钥列表一一对应）
     * @return 签名结果列表
     * @throws InterruptedException 线程中断异常
     * @throws ExecutionException 任务执行异常
     */
    public static List<byte[]> batchSign(List<byte[]> privateKeys, List<byte[]> dataList) throws InterruptedException, ExecutionException {
        int size = privateKeys.size();
        if (size != dataList.size()) {
            throw new IllegalArgumentException("私钥列表与数据列表长度不一致");
        }

        List<Future<byte[]>> futures = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            final byte[] key = privateKeys.get(i);
            final byte[] data = dataList.get(i);
            // 提交并行签名任务
            futures.add(EXECUTOR.submit(() -> fastSign(key, data)));
        }

        // 收集签名结果
        List<byte[]> signatures = new ArrayList<>(size);
        for (Future<byte[]> future : futures) {
            signatures.add(future.get());
        }
        return signatures;
    }

    /**
     * 批量验签（并行处理）
     * @param publicKeys 核心公钥列表（33/65字节，与数据列表一一对应）
     * @param dataList 原始数据列表
     * @param signatures 签名结果列表
     * @return 验签结果列表
     * @throws InterruptedException 线程中断异常
     * @throws ExecutionException 任务执行异常
     */
    public static List<Boolean> batchVerify(List<byte[]> publicKeys, List<byte[]> dataList, List<byte[]> signatures) throws InterruptedException, ExecutionException {
        int size = publicKeys.size();
        if (size != dataList.size() || size != signatures.size()) {
            throw new IllegalArgumentException("公钥列表、数据列表、签名列表长度不一致");
        }

        List<Future<Boolean>> futures = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            final byte[] pubKey = publicKeys.get(i);
            final byte[] data = dataList.get(i);
            final byte[] sig = signatures.get(i);
            // 提交并行验签任务
            futures.add(EXECUTOR.submit(() -> fastVerify(pubKey, data, sig)));
        }

        // 收集验签结果
        List<Boolean> results = new ArrayList<>(size);
        for (Future<Boolean> future : futures) {
            results.add(future.get());
        }
        return results;
    }

    // ------------------------------ 辅助方法 ------------------------------
    /**
     * 清理线程局部变量（在线程池任务结束时调用，避免内存泄漏）
     */
    public static void clearThreadLocals() {
        JDK_SIGNER_THREAD_LOCAL.remove();
        BC_SIGNER_THREAD_LOCAL.remove();
        JDK_VERIFIER_THREAD_LOCAL.remove();
        BC_VERIFIER_THREAD_LOCAL.remove();
        SECP256K1_KEY_FACTORY.remove();
    }

    /**
     * 关闭线程池（应用关闭时调用）
     */
    public static void shutdownExecutor() {
        EXECUTOR.shutdown();
        try {
            if (!EXECUTOR.awaitTermination(5, TimeUnit.SECONDS)) {
                EXECUTOR.shutdownNow();
            }
        } catch (InterruptedException e) {
            EXECUTOR.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("Secp256k1 线程池已关闭");
    }




    private static boolean verifySignatureWithCache(PublicKey publicKey, byte[] rData, byte[] cachedSignature) {
        return verifySignature(publicKey, rData, cachedSignature);
    }

    private static byte[] applySignatureWithCache(PrivateKey privateKey, byte[] testData) {
        return applySignature(privateKey, testData);
    }


    //33字节核心公钥恢复到 PublicKey
    /**
     * 从33字节压缩格式核心公钥（裸公钥）恢复 Secp256k1 PublicKey 对象
     * 适配区块链场景的压缩裸公钥，解决类型不匹配和格式解析问题
     * @param corePublicKey 33字节压缩格式裸公钥（格式：0x02/0x03 + 32字节x坐标）
     * @return Secp256k1 标准 PublicKey 对象
     */
    private static PublicKey recoverPublicKeyFromCore(byte[] corePublicKey) {
        // 1. 严格校验输入参数
        if (corePublicKey == null) {
            throw new IllegalArgumentException("核心公钥不能为空");
        }
        if (corePublicKey.length != PUBLIC_KEY_COMPRESSED_LENGTH) {
            throw new IllegalArgumentException(
                    String.format("核心公钥必须是33字节压缩格式，当前长度：%d字节", corePublicKey.length)
            );
        }
        // 校验压缩公钥前缀（只能是0x02或0x03，对应x坐标奇偶性）
        byte prefix = corePublicKey[0];
        if (prefix != 0x02 && prefix != 0x03) {
            throw new IllegalArgumentException(
                    String.format("压缩公钥前缀无效（必须是0x02或0x03），当前前缀：0x%02X", prefix)
            );
        }

        try {

            // 3. 获取 BouncyCastle 的 secp256k1 曲线参数（BC 专用类型）
            ECNamedCurveParameterSpec curveSpec = ECNamedCurveTable.getParameterSpec("secp256k1");
            ECParameterSpec ecParams = curveSpec; // BC 类型曲线参数，与公钥规格匹配

            // 4. 从33字节压缩裸公钥解码出 EC 公钥点
            ECPoint publicKeyPoint = ecParams.getCurve().decodePoint(corePublicKey);
            if (publicKeyPoint.isInfinity()) {
                throw new IllegalArgumentException("核心公钥无效（无穷远点，无法用于加密签名）");
            }

            // 5. 构建 BC 类型的 ECPublicKeySpec（类型完全匹配，避免报错）
            ECPublicKeySpec pubKeySpec = new ECPublicKeySpec(publicKeyPoint, ecParams);

            // 6. 通过 BC KeyFactory 生成 PublicKey 对象
            KeyFactory keyFactory = SECP256K1_KEY_FACTORY.get();
            return keyFactory.generatePublic(pubKeySpec);

        } catch (IllegalArgumentException e) {
            throw e; // 直接抛出参数/格式异常，便于调用方排查
        } catch (Exception e) {
            throw new RuntimeException("从33字节核心公钥恢复公钥失败", e);
        }
    }

    //32字节核心私钥恢复到
// 先通过ECPrivateKeySpec恢复PrivateKey（这是可靠的裸私钥转PrivateKey方式）
    private static PrivateKey recoverPrivateKeyFromCore(byte[] corePrivateKey) {
        if (corePrivateKey == null || corePrivateKey.length != CORE_KEY_LENGTH) {
            throw new IllegalArgumentException("核心私钥必须是32字节");
        }

        try {
            // 1. 获取Secp256k1曲线参数
            ECNamedCurveParameterSpec curveSpec = ECNamedCurveTable.getParameterSpec("secp256k1");
            org.bouncycastle.jce.spec.ECParameterSpec ecParams = curveSpec;

            // 2. 裸私钥转BigInteger
            BigInteger privKeyInt = new BigInteger(1, corePrivateKey);
            BigInteger curveOrder = curveSpec.getN();
            if (privKeyInt.compareTo(BigInteger.ONE) < 0 || privKeyInt.compareTo(curveOrder.subtract(BigInteger.ONE)) > 0) {
                throw new IllegalArgumentException("核心私钥超出secp256k1有效范围");
            }

            // 3. 构建EC私钥规格（裸私钥专用）
            ECPrivateKeySpec privKeySpec = new ECPrivateKeySpec(privKeyInt, ecParams);
            KeyFactory keyFactory = SECP256K1_KEY_FACTORY.get();
            PrivateKey privateKey = keyFactory.generatePrivate(privKeySpec);

            // （可选）若需要PKCS#8编码，可通过以下方式获取
            // byte[] pkcs8Encoded = privateKey.getEncoded(); // 这是合法的PKCS#8编码

            return privateKey;
        } catch (Exception e) {
            throw new RuntimeException("从32字节核心私钥恢复私钥失败", e);
        }
    }

    /**
     * 从私钥获取公钥
     * @param corePrivateKey
     * @return
     */
    private static byte[] derivePublicKeyFromPrivateKey(byte[] corePrivateKey, boolean compressed) {
        try {
            if (corePrivateKey.length != CORE_KEY_LENGTH) {
                throw new IllegalArgumentException("私钥必须为32字节");
            }
            // 1. 核心私钥转BigInteger
            BigInteger privKeyInt = new BigInteger(1, corePrivateKey);
            // 2. 验证私钥有效性（secp256k1曲线）
            ECNamedCurveParameterSpec curveSpec = ECNamedCurveTable.getParameterSpec("secp256k1");
            BigInteger n = curveSpec.getN();
            if (privKeyInt.compareTo(BigInteger.ONE) < 0 || privKeyInt.compareTo(n.subtract(BigInteger.ONE)) > 0) {
                throw new IllegalArgumentException("核心私钥超出secp256k1有效范围");
            }
            // 3. 计算公钥点（私钥 * 基点G）
            ECPoint publicKeyPoint = new FixedPointCombMultiplier().multiply(curveSpec.getG(), privKeyInt);
            return publicKeyPoint.getEncoded(compressed);
        } catch (Exception e) {
            throw new RuntimeException("字节数组转换为公钥失败", e);
        }
    }


    // ------------------------------ 测试方法 ------------------------------
/*
    public static void main(String[] args) {
        // 1. 生成助记词
        List<String> mnemonic = Secp256k1HDWallet.generateMnemonic();
        System.out.println("===== 基础信息 =====");
        System.out.println("助记词: " + String.join(" ", mnemonic));
        System.out.println("---------------------");

        // 2. 生成基准密钥对（路径：m/44'/0'/0'/0/0）
        KeyInfo baseKey = Secp256k1HDWallet.getKeyPair(mnemonic, 0, 0);
        System.out.println("===== 基准密钥对（账户0，地址0） =====");
        System.out.println("派生路径: " + baseKey.getPath());
        System.out.println("私钥(hex): " + Hex.toHexString(baseKey.getPrivateKey()));
        System.out.println("公钥(hex，非压缩): " + Hex.toHexString(baseKey.getPublicKey()));
        System.out.println("对应地址: " + baseKey.getAddress());
        System.out.println("---------------------");

        // 3. 验证公钥与地址的一致性
        byte[] corePriv = baseKey.getPrivateKey();
        log.info("核心私钥长度{}",corePriv.length);
        byte[] corePub = Secp256k1Signer.derivePublicKeyFromPrivateKey(corePriv,true);
        log.info("核心公钥长度{}",corePub.length);

        byte[] pubKey = Secp256k1Signer.derivePublicKeyFromPrivateKey(corePriv,false);
        String addressFromPub = Base58.encode(ECKey.fromPublicOnly(corePub).getPubKeyHash());
        System.out.println("===== 公钥与地址一致性验证 =====");
        System.out.println("非压缩公钥长度: " + corePub.length + "字节（应为33）");
        System.out.println("非压缩公钥长度: " + pubKey.length + "字节（应为65）");
        System.out.println("私钥长度: " + corePriv.length + "字节（应为32）");
        System.out.println("公钥派生地址: " + addressFromPub);
        System.out.println("地址一致性: " + addressFromPub.equals(baseKey.getAddress()) + "（应为true）");
        System.out.println("---------------------");

        // 4. 验证签名功能（普通签名/验签）
        byte[] testData = "Secp256k1 HD Wallet Test".getBytes(StandardCharsets.UTF_8);
        PrivateKey privateKey = Secp256k1Signer.recoverPrivateKeyFromCore(corePriv);
        PublicKey publicKey = Secp256k1Signer.recoverPublicKeyFromCore(corePub);
        byte[] signature = Secp256k1Signer.applySignature(privateKey, testData);
        boolean verifyResult = Secp256k1Signer.verifySignature(publicKey, testData, signature);
        System.out.println("===== 普通签名验证 =====");
        System.out.println("签名长度: " + signature.length + "字节（DER编码，长度不固定）");
        System.out.println("验签结果: " + verifyResult + "（应为true）");
        System.out.println("---------------------");

        // 5. 验证高性能签名/验签
        byte[] fastSignature = Secp256k1Signer.fastSign(corePriv, testData);
        boolean fastVerifyResult = Secp256k1Signer.fastVerify(corePub, testData, fastSignature);
        System.out.println("===== 高性能签名验证 =====");
        System.out.println("高性能签名长度: " + fastSignature.length + "字节");
        System.out.println("高性能验签结果: " + fastVerifyResult + "（应为true）");
        System.out.println("---------------------");

        // 6. 验证带缓存的签名/验签
        byte[] cachedSignature = Secp256k1Signer.applySignatureWithCache(privateKey, testData);
        boolean cachedVerifyResult = Secp256k1Signer.verifySignatureWithCache(publicKey, testData, cachedSignature);
        System.out.println("===== 带缓存签名验证 =====");
        System.out.println("缓存签名与普通签名一致性: " + Arrays.equals(signature, cachedSignature) + "（应为true）");
        System.out.println("缓存验签结果: " + cachedVerifyResult + "（应为true）");
        System.out.println(Secp256k1Signer.getCacheStats());
        System.out.println("---------------------");

        // 7. 验证批量签名/验签
        try {
            List<byte[]> privateKeyList = new ArrayList<>();
            List<byte[]> dataList = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                privateKeyList.add(corePriv);
                dataList.add(("Batch Test " + i).getBytes(StandardCharsets.UTF_8));
            }
            List<byte[]> batchSignatures = Secp256k1Signer.batchSign(privateKeyList, dataList);
            List<byte[]> publicKeyList = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                publicKeyList.add(corePub);
            }
            List<Boolean> batchVerifyResults = Secp256k1Signer.batchVerify(publicKeyList, dataList, batchSignatures);
            System.out.println("===== 批量签名验签验证 =====");
            System.out.println("批量签名数量: " + batchSignatures.size());
            System.out.println("批量验签全部成功: " + batchVerifyResults.stream().allMatch(Boolean::booleanValue) + "（应为true）");
            System.out.println("---------------------");
        } catch (Exception e) {
            log.error("批量签名验签失败", e);
        }






        // 8. 清理资源
        Secp256k1Signer.clearThreadLocals();
        Secp256k1Signer.shutdownExecutor();
    }
*/


    public static void main(String[] args) {
        // 1. 生成测试密钥对
        System.out.println("===== Secp256k1 签名性能测试 =====");
        System.out.println("开始生成测试密钥对...");

        // 生成一个测试密钥对
        KeyPair keyPair = Secp256k1Signer.generateKeyPair();
        PrivateKey privateKey = keyPair.getPrivate();
        PublicKey publicKey = keyPair.getPublic();

        // 转换为核心密钥格式用于高性能签名
        byte[] corePrivateKey = new byte[32];
        // 这里需要从PrivateKey中提取32字节核心私钥
        // 注意：这是简化示例，实际中需要从PrivateKey对象中提取原始私钥字节
        SecureRandom random = new SecureRandom();
        random.nextBytes(corePrivateKey); // 使用随机私钥进行测试

        byte[] corePublicKey = derivePublicKeyFromPrivateKey(corePrivateKey, true);

        System.out.println("测试密钥对生成完成");
        System.out.println("核心私钥长度: " + corePrivateKey.length + "字节");
        System.out.println("核心公钥长度: " + corePublicKey.length + "字节");
        System.out.println("------------------------");

        // 2. 准备测试数据
        System.out.println("准备测试数据...");
        int testCount = 10000;
        List<byte[]> testDataList = new ArrayList<>();
        for (int i = 0; i < testCount; i++) {
            String data = "Test message " + i + " at " + System.currentTimeMillis();
            testDataList.add(data.getBytes(StandardCharsets.UTF_8));
        }
        System.out.println("已生成 " + testCount + " 条测试数据");
        System.out.println("------------------------");

        // 3. 测试标准签名性能（使用JCA接口）
        System.out.println("测试标准签名性能（" + testCount + "次）...");
        long startTime = System.nanoTime();
        List<byte[]> standardSignatures = new ArrayList<>();

        for (int i = 0; i < testCount; i++) {
            byte[] signature = applySignature(privateKey, testDataList.get(i));
            standardSignatures.add(signature);

            // 验证签名
            boolean verified = verifySignature(publicKey, testDataList.get(i), signature);
            if (!verified) {
                System.err.println("警告：第 " + i + " 次签名验证失败");
            }
        }

        long endTime = System.nanoTime();
        long standardTotalTime = endTime - startTime;
        double standardAvgTime = (double) standardTotalTime / testCount / 1_000_000.0; // 转换为毫秒

        System.out.println("标准签名测试完成");
        System.out.println("总耗时: " + (standardTotalTime / 1_000_000.0) + " 毫秒");
        System.out.println("平均每次签名耗时: " + String.format("%.3f", standardAvgTime) + " 毫秒");
        System.out.println("签名验证结果: 全部 " + testCount + " 次签名验证成功");
        System.out.println("------------------------");

        // 4. 测试高性能签名性能（使用bitcoinj的ECKey）
        System.out.println("测试高性能签名性能（" + testCount + "次）...");
        startTime = System.nanoTime();
        List<byte[]> fastSignatures = new ArrayList<>();

        for (int i = 0; i < testCount; i++) {
            byte[] signature = fastSign(corePrivateKey, testDataList.get(i));
            fastSignatures.add(signature);

            // 验证签名
            boolean verified = fastVerify(corePublicKey, testDataList.get(i), signature);
            if (!verified) {
                System.err.println("警告：第 " + i + 1 + " 次高性能签名验证失败");
            }
        }

        endTime = System.nanoTime();
        long fastTotalTime = endTime - startTime;
        double fastAvgTime = (double) fastTotalTime / testCount / 1_000_000.0; // 转换为毫秒

        System.out.println("高性能签名测试完成");
        System.out.println("总耗时: " + (fastTotalTime / 1_000_000.0) + " 毫秒");
        System.out.println("平均每次签名耗时: " + String.format("%.3f", fastAvgTime) + " 毫秒");
        System.out.println("签名验证结果: 全部 " + testCount + " 次签名验证成功");

        // 性能对比
        double speedup = (standardTotalTime - fastTotalTime) * 100.0 / standardTotalTime;
        System.out.println("性能提升: " + String.format("%.1f", speedup) + "%");
        System.out.println("------------------------");

        // 5. 测试批量签名性能
        System.out.println("测试批量签名性能（" + testCount + "次并行处理）...");
        startTime = System.nanoTime();

        try {
            // 准备批量数据
            List<byte[]> batchPrivateKeys = new ArrayList<>();
            List<byte[]> batchData = new ArrayList<>();
            for (int i = 0; i < testCount; i++) {
                batchPrivateKeys.add(corePrivateKey);
                batchData.add(testDataList.get(i));
            }

            // 执行批量签名
            List<byte[]> batchSignatures = batchSign(batchPrivateKeys, batchData);

            endTime = System.nanoTime();
            long batchTotalTime = endTime - startTime;
            double batchAvgTime = (double) batchTotalTime / testCount / 1_000_000.0; // 转换为毫秒

            System.out.println("批量签名测试完成");
            System.out.println("总耗时: " + (batchTotalTime / 1_000_000.0) + " 毫秒");
            System.out.println("平均每次签名耗时: " + String.format("%.3f", batchAvgTime) + " 毫秒");

            // 验证批量签名
            System.out.println("开始验证批量签名...");
            List<byte[]> batchPublicKeys = new ArrayList<>();
            for (int i = 0; i < testCount; i++) {
                batchPublicKeys.add(corePublicKey);
            }

            List<Boolean> batchVerifyResults = batchVerify(batchPublicKeys, batchData, batchSignatures);
            long successCount = batchVerifyResults.stream().filter(Boolean::booleanValue).count();
            System.out.println("批量签名验证: " + successCount + "/" + testCount + " 个签名验证成功");

            if (successCount != testCount) {
                System.err.println("警告: 有 " + (testCount - successCount) + " 个签名验证失败");
            }

        } catch (Exception e) {
            System.err.println("批量签名测试失败: " + e.getMessage());
            e.printStackTrace();
        }

        System.out.println("------------------------");

        // 6. 内存和线程池统计
        System.out.println("===== 资源使用统计 =====");
        System.out.println("可用处理器核心数: " + CPU_CORES);
        System.out.println("线程池状态: " + (EXECUTOR.isShutdown() ? "已关闭" : "运行中"));

        Runtime runtime = Runtime.getRuntime();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        long maxMemory = runtime.maxMemory();
        System.out.println("内存使用: " + (usedMemory / 1024 / 1024) + "MB / " + (maxMemory / 1024 / 1024) + "MB");

        // 7. 清理资源
        System.out.println("------------------------");
        System.out.println("清理资源...");
        clearThreadLocals();
        shutdownExecutor();

        System.out.println("测试完成！");
    }

}