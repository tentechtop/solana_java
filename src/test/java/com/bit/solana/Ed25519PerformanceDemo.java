package com.bit.solana;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.security.Security;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class Ed25519PerformanceDemo {
    // 线程局部缓存签名器和验证器（复用实例，减少创建开销）
    private static final ThreadLocal<Ed25519Signer> SIGNER_THREAD_LOCAL = ThreadLocal.withInitial(Ed25519Signer::new);
    private static final ThreadLocal<Ed25519Signer> VERIFIER_THREAD_LOCAL = ThreadLocal.withInitial(Ed25519Signer::new);

    // CPU核心数（Ed25519是CPU密集型操作，线程数与核心数匹配）
    private static final int CPU_CORES = Runtime.getRuntime().availableProcessors();
    // 线程池（固定大小，避免线程创建销毁开销）
    private static final ExecutorService EXECUTOR = new ThreadPoolExecutor(
            CPU_CORES,
            CPU_CORES,
            0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(100000), // 大缓冲队列
            new ThreadFactory() {
                private final AtomicInteger seq = new AtomicInteger(0);
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "ed25519-worker-" + seq.getAndIncrement());
                    t.setDaemon(true);
                    t.setPriority(Thread.NORM_PRIORITY + 1); // 提高优先级
                    return t;
                }
            },
            new ThreadPoolExecutor.CallerRunsPolicy() // 队列满时调用方执行，避免任务丢失
    );

    static {
        // 注册BouncyCastle Provider（确保优先使用）
        Security.addProvider(new BouncyCastleProvider());
    }

    // 高性能签名：直接使用BouncyCastle底层API，复用签名器
    public static byte[] fastSign(byte[] privateKey, byte[] data) {
        Ed25519Signer signer = SIGNER_THREAD_LOCAL.get();
        Ed25519PrivateKeyParameters keyParams = new Ed25519PrivateKeyParameters(privateKey, 0);
        signer.init(true, keyParams);
        signer.update(data, 0, data.length);
        return signer.generateSignature();
    }

    // 高性能验签：直接使用BouncyCastle底层API，复用验证器
    public static boolean fastVerify(byte[] publicKey, byte[] data, byte[] signature) {
        Ed25519Signer verifier = VERIFIER_THREAD_LOCAL.get();
        Ed25519PublicKeyParameters keyParams = new Ed25519PublicKeyParameters(publicKey, 0);
        verifier.init(false, keyParams);
        verifier.update(data, 0, data.length);
        return verifier.verifySignature(signature);
    }

    // 批量签名（并行处理）
    public static List<byte[]> batchSign(List<byte[]> privateKeys, List<byte[]> dataList) throws InterruptedException, ExecutionException {
        int size = privateKeys.size();
        List<Future<byte[]>> futures = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            final byte[] key = privateKeys.get(i);
            final byte[] data = dataList.get(i);
            futures.add(EXECUTOR.submit(() -> fastSign(key, data)));
        }
        // 收集结果
        List<byte[]> signatures = new ArrayList<>(size);
        for (Future<byte[]> future : futures) {
            signatures.add(future.get());
        }
        return signatures;
    }

    // 批量验签（并行处理）
    public static List<Boolean> batchVerify(List<byte[]> publicKeys, List<byte[]> dataList, List<byte[]> signatures) throws InterruptedException, ExecutionException {
        int size = publicKeys.size();
        List<Future<Boolean>> futures = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            final byte[] pubKey = publicKeys.get(i);
            final byte[] data = dataList.get(i);
            final byte[] sig = signatures.get(i);
            futures.add(EXECUTOR.submit(() -> fastVerify(pubKey, data, sig)));
        }
        // 收集结果
        List<Boolean> results = new ArrayList<>(size);
        for (Future<Boolean> future : futures) {
            results.add(future.get());
        }
        return results;
    }

    // 生成测试数据（随机私钥、公钥、待签名数据）
    private static void generateTestData(int count, List<byte[]> privateKeys, List<byte[]> publicKeys, List<byte[]> dataList) {
        Random random = new Random();
        for (int i = 0; i < count; i++) {
            // 生成随机32字节私钥
            byte[] privateKey = new byte[32];
            random.nextBytes(privateKey);
            privateKeys.add(privateKey);

            // 从私钥派生公钥（Ed25519公钥可由私钥直接计算）
            Ed25519PrivateKeyParameters privParams = new Ed25519PrivateKeyParameters(privateKey, 0);
            byte[] publicKey = privParams.generatePublicKey().getEncoded();
            publicKeys.add(publicKey);

            // 生成随机待签名数据（128字节）
            byte[] data = new byte[128];
            random.nextBytes(data);
            dataList.add(data);
        }
    }

    public static void main(String[] args) throws Exception {
        // 测试参数（可根据机器性能调整）
        int testCount = 100000; // 总测试数量
        int batchSize = 1000;   // 每批处理数量

        // 生成测试数据
        List<byte[]> privateKeys = new ArrayList<>(testCount);
        List<byte[]> publicKeys = new ArrayList<>(testCount);
        List<byte[]> dataList = new ArrayList<>(testCount);
        System.out.println("生成 " + testCount + " 条测试数据...");
        generateTestData(testCount, privateKeys, publicKeys, dataList);
        System.out.println("测试数据生成完成\n");

        // 测试批量签名性能
        System.out.println("开始测试签名性能（" + testCount + " 条）...");
        long signStart = System.currentTimeMillis();
        List<byte[]> signatures = new ArrayList<>(testCount);
        // 分批处理（避免单次提交过多任务导致内存占用过高）
        for (int i = 0; i < testCount; i += batchSize) {
            int end = Math.min(i + batchSize, testCount);
            List<byte[]> batchPrivKeys = privateKeys.subList(i, end);
            List<byte[]> batchData = dataList.subList(i, end);
            signatures.addAll(batchSign(batchPrivKeys, batchData));
        }
        long signEnd = System.currentTimeMillis();
        long signTime = signEnd - signStart;
        double signQps = testCount / (signTime / 1000.0);
        System.out.printf("签名完成：耗时 %d ms，QPS: %.2f\n", signTime, signQps);

        // 测试批量验签性能
        System.out.println("\n开始测试验签性能（" + testCount + " 条）...");
        long verifyStart = System.currentTimeMillis();
        List<Boolean> verifyResults = new ArrayList<>(testCount);
        for (int i = 0; i < testCount; i += batchSize) {
            int end = Math.min(i + batchSize, testCount);
            List<byte[]> batchPubKeys = publicKeys.subList(i, end);
            List<byte[]> batchData = dataList.subList(i, end);
            List<byte[]> batchSigs = signatures.subList(i, end);
            verifyResults.addAll(batchVerify(batchPubKeys, batchData, batchSigs));
        }
        long verifyEnd = System.currentTimeMillis();
        long verifyTime = verifyEnd - verifyStart;
        double verifyQps = testCount / (verifyTime / 1000.0);
        System.out.printf("验签完成：耗时 %d ms，QPS: %.2f\n", verifyTime, verifyQps);

        // 验证结果正确性（确保没有验签失败）
        boolean allSuccess = verifyResults.stream().allMatch(b -> b);
        System.out.println("\n验签结果正确性：" + (allSuccess ? "全部成功" : "存在失败"));

        // 关闭线程池
        EXECUTOR.shutdown();
    }
}