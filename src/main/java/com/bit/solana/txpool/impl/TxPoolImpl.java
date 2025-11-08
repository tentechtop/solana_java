package com.bit.solana.txpool.impl;

import com.bit.solana.common.BlockHash;
import com.bit.solana.result.Result;
import com.bit.solana.structure.account.AccountMeta;
import com.bit.solana.structure.bloom.AccountConflictBloom;
import com.bit.solana.structure.tx.*;
import com.bit.solana.txpool.TxPool;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.w3c.dom.css.Counter;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

@Slf4j
@Service
public class TxPoolImpl implements TxPool {
    // ==================== 核心配置参数 ====================
    // Disruptor环形缓冲区大小（2^20=1,048,576，支撑10万TPS缓冲）
    private static final int RING_BUFFER_SIZE = 1 << 20;
    // 处理线程数（按1万TPS估算）
    private static final int PROCESS_THREAD_COUNT = Runtime.getRuntime().availableProcessors() * 4;
    // 分片数量（建议为2的幂，哈希分布更均匀）
    private static final int SHARD_COUNT = 32;
    // 等待队列容量（背压机制）
    private static final int QUEUE_CAPACITY = 200_000;
    // 失败重试次数
    private static final int MAX_RETRY = 3;
    // 单个交易处理超时时间（500ms）
    private static final long TIMEOUT_MILLIS = 500;
    // 超时检查间隔（100ms）
    private static final long CHECK_TIMEOUT_INTERVAL = 100;
    // 最大交易池大小，防止内存溢出
    private static final int MAX_POOL_SIZE = 1_000_000;
    // 清理过期交易的间隔时间
    private static final long CLEANUP_INTERVAL = 5_000;
    // 签名验证缓存大小
    private static final int SIGNATURE_CACHE_SIZE = 100_000;
    // 签名验证缓存过期时间（分钟）
    private static final long SIGNATURE_CACHE_EXPIRE_MINUTES = 10;


    // ==================== 核心组件 ====================
    // 高并发接收队列（Disruptor无锁环形缓冲区）
    private Disruptor<Transaction> disruptor;
    private RingBuffer<Transaction> ringBuffer;
    // 处理线程池（工作窃取算法）
    private ForkJoinPool processPool;
    // 提交线程池（独立线程池隔离）
    private ThreadPoolExecutor submitPool;
    // 超时检查定时器
    private ScheduledExecutorService timeoutChecker;
    // 清理过期交易定时器
    private ScheduledExecutorService cleanupScheduler;
    // TPS统计定时器
    private ScheduledExecutorService tpsStatScheduler;
    // 分片定位（根据byte[]的哈希值）
    private int getShardIndex(byte[] key) {
        return Math.abs(Arrays.hashCode(key) % SHARD_COUNT);
    }
    // 交易池（主缓存）
    private final Map<byte[], Transaction> txMap  = new ConcurrentHashMap<>();
    // 交易分组（groupId -> 交易组，groupId通常为sender账户）
    private final ConcurrentMap<byte[], TransactionGroup> txGroups = new ConcurrentHashMap<>();
    // 并行处理线程池（核心线程数=CPU核心数）
    private final ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    // 消息摘要器（线程局部变量避免竞争）
    private ThreadLocal<MessageDigest> sha256Digester;
    // 签名验证缓存（LRU策略）
    private LoadingCache<String, Boolean> signatureCache;


    // ==================== 数据存储 ====================
    // 交易状态追踪（原子操作保证线程安全）
    private final ConcurrentMap<byte[], TransactionStatus> txStatusMap = new ConcurrentHashMap<>();
    // 处理中交易追踪（原子操作，线程安全）
    private final ConcurrentHashMap<String, Transaction> processingTxs = new ConcurrentHashMap<>();
    // 交易池分片（每个分片独立锁）
    private final List<Map<byte[], Transaction>> txShards = new ArrayList<>();
    // 交易组分片（按groupId哈希分片）
    private final List<ConcurrentMap<byte[], TransactionGroup>> groupShards = new ArrayList<>();
    // 双花检查：已处理的交易ID，防止重复处理
    private final Set<byte[]> processedTxIds = ConcurrentHashMap.newKeySet();
    // 账户冲突布隆过滤器，用于快速检测潜在的账户冲突
    private final AccountConflictBloom accountConflictBloom = AccountConflictBloom.createEmpty();
    // 交易池大小计数器
    private final AtomicInteger poolSize = new AtomicInteger(0);


    // 阻塞队列用于削峰填谷（防止突发流量击垮系统）
    private final BlockingQueue<Transaction> submitQueue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    // 并行处理线程池（工作窃取算法，适合大量小任务）
    private final ExecutorService processingPool = new ForkJoinPool(
            Runtime.getRuntime().availableProcessors() * 2,
            ForkJoinPool.defaultForkJoinWorkerThreadFactory,
            (t, e) -> log.error("交易处理线程异常", e),
            true
    );

    // ==================== 性能指标 ====================
    /** 提交交易总数 */
    private final LongAdder submittedCount = new LongAdder();
    /** 处理成功总数 */
    private final LongAdder processedCount = new LongAdder();
    /** 超时交易总数 */
    private final LongAdder timeoutCount = new LongAdder();
    /** 失败交易总数 */
    private final LongAdder failedCount = new LongAdder();
    /** 当前TPS（每秒滑动窗口） */
    private final AtomicLong currentTps = new AtomicLong();
    /** 上一次统计时间 */
    private volatile long lastStatTime = System.currentTimeMillis();
    /** 上一次统计的处理数量 */
    private volatile long lastProcessedCount = 0;


    /**
     * Disruptor事件对象，用于无锁传递交易
     */
    private static class TransactionEvent {
        private Transaction transaction;
        private CompletableFuture<Result<String>> future;

        public Transaction getTransaction() {
            return transaction;
        }

        public void setTransaction(Transaction transaction) {
            this.transaction = transaction;
        }

        public CompletableFuture<Result<String>> getFuture() {
            return future;
        }

        public void setFuture(CompletableFuture<Result<String>> future) {
            this.future = future;
        }
    }


    @PostConstruct
    public void init(){
        // 初始化分片
        for (int i = 0; i < SHARD_COUNT; i++) {
            txShards.add(new ConcurrentHashMap<>());
            groupShards.add(new ConcurrentHashMap<>());
        }
        // 初始化SHA-256消息摘要器（线程局部变量）
        sha256Digester = ThreadLocal.withInitial(() -> {
            try {
                return MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException("初始化SHA-256失败", e);
            }
        });
        // 初始化签名验证缓存（LRU策略，带过期时间）
        signatureCache = CacheBuilder.newBuilder()
                .maximumSize(SIGNATURE_CACHE_SIZE)
                .expireAfterWrite(SIGNATURE_CACHE_EXPIRE_MINUTES, TimeUnit.MINUTES)
                .concurrencyLevel(Runtime.getRuntime().availableProcessors())
                .recordStats()
                .build(new CacheLoader<>() {
                    @Override
                    public Boolean load(String key) {
                        // 实际签名验证逻辑，这里仅做示例
                        return verifySignatureInternal(key);
                    }
                });



    }



    @Override
    public Result getTxPool() {
        return null;
    }

    @Override
    public TransactionStatus getStatus(String txId) {
        return null;
    }

    @Override
    public Result getTxPoolStatus() {
        return null;
    }

    /**
     * 异步验证 + 预验证
     * 轻量预验证（格式、签名合法性）在addTransaction时同步快速完成，过滤明显无效的交易。
     * 重量级验证（余额检查、双花检测）交给线程池异步执行，验证通过后再正式加入交易池。
     * @param tx
     * @return
     */
    @Override
    public Result verifyTransaction(Transaction tx)  {
        // 1. 轻量预验证（同步，快速失败）
        Result<String> preCheck = preVerify(tx);
        if (!preCheck.isSuccess()) {
            return preCheck;
        }
        // 2. 异步执行重量级验证+加入交易池
        return Result.OK("交易已接受，正在验证");
    }

    /**
     * 带超时控制
     * @param tx
     * @return
     */
    @Override
    public Result addTransaction(Transaction tx) {
        return Result.OK();
    }

    /**
     * 不同组 无冲突 可并行
     */
    @Override
    public void processTransactions() {
        // 1. 从所有分片收集待处理的交易组（过滤空组）
        List<TransactionGroup> groups = groupShards.stream()
                .flatMap(shard -> shard.values().stream())
                .filter(group -> !group.getTransactions().isEmpty())
                .toList();


    }


    /**
     * 处理单个交易组（组内交易串行处理）
     * @param group
     */
    private void processGroup(TransactionGroup group) {
        // 按POH时间戳排序组内交易（确保顺序性）
        // 串行处理组内交易
        // 交易验证（签名校验、余额检查等）

    }


    // 轻量预验证（格式、签名结构等）
    /**
     * 轻量预验证（格式、签名结构等）
     */
    private Result<String> preVerify(Transaction tx) {
        if (tx == null) {
            return Result.error("交易为空");
        }

        if (tx.getSignatures() == null || tx.getSignatures().isEmpty()) {
            return Result.error("缺少签名");
        }

        // 检查签名长度（每个签名应为64字节）
        for (Signature sig : tx.getSignatures()) {
            if (sig.getValue() == null || sig.getValue().length != 64) {
                return Result.error("签名长度无效，必须为64字节");
            }
        }

        // 检查账户列表
        if (tx.getAccounts() == null || tx.getAccounts().isEmpty()) {
            return Result.error("交易未包含任何账户");
        }

        // 检查最近区块哈希
        if (tx.getRecentBlockhash() == null || tx.getRecentBlockhash().getValue() == null) {
            return Result.error("缺少最近区块哈希");
        }

        // 检查指令列表
        if (tx.getInstructions() == null || tx.getInstructions().isEmpty()) {
            return Result.error("交易未包含任何指令");
        }

        // 检查指令合法性
        for (Instruction instr : tx.getInstructions()) {
            if (instr.getProgramId() == null || instr.getProgramId().length != 32) {
                return Result.error("程序ID无效，必须为32字节");
            }
        }

        // 检查交易ID是否有效
        try {
            tx.getTxId(); // 触发txId生成
        } catch (Exception e) {
            return Result.error("生成交易ID失败: " + e.getMessage());
        }

        return Result.OK();
    }




    //重量级验证
    private Result<String> fullVerify(Transaction tx) {
        try {
            // 1. 验证签名（使用缓存）
            if (!verifySignatures(tx)) {
                return Result.error("签名验证失败");
            }

            // 2. 检查双花
            if (processedTxIds.contains(tx.getTxId())) {
                return Result.error("交易已处理，可能存在双花");
            }

            // 3. 检查账户冲突（使用布隆过滤器快速判断）
            if (hasAccountConflicts(tx)) {
                return Result.error("交易存在账户冲突");
            }

            // 4. 检查余额是否充足（需要查询账户状态）
            if (!hasSufficientBalance(tx)) {
                return Result.error("余额不足");
            }

            // 5. 检查区块哈希是否过期（交易有效期）
            if (isBlockhashExpired(tx.getRecentBlockhash())) {
                return Result.error("区块哈希已过期，交易无效");
            }

            return Result.OK();
        } catch (Exception e) {
            log.error("交易重量级验证失败", e);
            return Result.error("验证失败: " + e.getMessage());
        }
    }


    /**
     * 实际签名验证逻辑（被缓存加载器调用）
     */
    private boolean verifySignatureInternal(String signatureKey) {
        try {
            // 解码签名
            byte[] signature = Base64.getDecoder().decode(signatureKey);

            // TODO: 实现实际的签名验证逻辑
            // 1. 获取对应的公钥
            // 2. 使用公钥验证签名
            // 这里仅做示例，返回true
            return true;
        } catch (Exception e) {
            log.error("验证签名失败", e);
            return false;
        }
    }


    /**
     * 验证交易签名（使用缓存）
     */
    private boolean verifySignatures(Transaction tx) {
        try {
            // 对每个签名进行验证
            for (Signature sig : tx.getSignatures()) {
                // 生成签名缓存键
                String sigKey = Base64.getEncoder().encodeToString(sig.getValue());
                // 从缓存获取验证结果，缓存没有则会调用load方法进行验证
                Boolean isValid = signatureCache.get(sigKey);
                if (!isValid) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            log.error("验证签名失败", e);
            return false;
        }
    }

    /**
     * 检查是否存在账户冲突
     */
    private boolean hasAccountConflicts(Transaction tx) {
        if (tx.getAccounts() == null) {
            return false;
        }

        // 检查交易涉及的可写账户是否已在布隆过滤器中
        for (AccountMeta account : tx.getAccounts()) {
            if (account.isWritable() && accountConflictBloom.mightContain(account.getPublicKey())) {
                // 可能存在冲突，需要进一步精确检查
                if (hasExactAccountConflict(tx, account.getPublicKey())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 精确检查账户冲突
     */
    private boolean hasExactAccountConflict(Transaction tx, byte[] accountPubKey) {
        // 遍历所有分片检查是否有涉及该账户的未处理交易
        for (Map<byte[], Transaction> shard : txShards) {
            for (Transaction existingTx : shard.values()) {
                if (existingTx.getAccounts() != null) {
                    for (AccountMeta existingAccount : existingTx.getAccounts()) {
                        if (existingAccount.isWritable() && Arrays.equals(existingAccount.getPublicKey(), accountPubKey)) {
                            return true; // 发现精确冲突
                        }
                    }
                }
            }
        }
        return false;
    }

    /**
     * 检查余额是否充足
     */
    private boolean hasSufficientBalance(Transaction tx) {
        // TODO: 实现实际的余额检查逻辑
        // 1. 获取费用支付者账户
        // 2. 查询账户余额
        // 3. 计算交易费用
        // 4. 检查余额是否大于等于交易费用
        return true; // 示例返回true
    }

    /**
     * 检查区块哈希是否过期
     */
    private boolean isBlockhashExpired(BlockHash blockhash) {
        // TODO: 实现实际的区块哈希过期检查
        // 1. 获取当前最新区块哈希
        // 2. 检查交易中的区块哈希是否在有效期内（通常为300个slot）
        return false; // 示例返回false，表示未过期
    }


    /**
     * 资源清理
     */
    @PreDestroy
    public void destroy() {
        log.info("开始关闭交易池...");

        // 关闭Disruptor
        if (disruptor != null) {
            disruptor.shutdown();
        }

        // 关闭线程池
        if (processPool != null) {
            processPool.shutdown();
        }

        if (submitPool != null) {
            submitPool.shutdown();
        }

        // 关闭定时器
        if (timeoutChecker != null) {
            timeoutChecker.shutdown();
        }

        if (cleanupScheduler != null) {
            cleanupScheduler.shutdown();
        }

        if (tpsStatScheduler != null) {
            tpsStatScheduler.shutdown();
        }

        // 关闭处理线程池
        processingPool.shutdown();

        log.info("交易池已关闭");
    }
}
