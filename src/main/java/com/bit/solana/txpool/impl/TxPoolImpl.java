package com.bit.solana.txpool.impl;

import com.bit.solana.common.BlockHash;
import com.bit.solana.common.TransactionHash;
import com.bit.solana.structure.poh.POHRecord;
import com.bit.solana.poh.POHService;
import com.bit.solana.result.Result;
import com.bit.solana.structure.account.AccountMeta;
import com.bit.solana.structure.bloom.AccountConflictBloom;
import com.bit.solana.structure.tx.*;
import com.bit.solana.txpool.TxPool;
import com.bit.solana.util.ByteUtils;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.CacheStats;
import com.google.common.cache.LoadingCache;
import com.lmax.disruptor.*;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import static com.bit.solana.util.ByteUtils.bytesToHex;
import static com.bit.solana.util.ByteUtils.hexToBytes;

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
    // 交易过期时间（秒）- 基于recentBlockhash的有效性
    private static final long TX_EXPIRE_SECONDS = 120;


    // ==================== 核心组件 ====================
    @Autowired
    private POHService pohService;
    // 高并发接收队列（Disruptor无锁环形缓冲区）
    private Disruptor<TransactionEvent> disruptor;
    private RingBuffer<TransactionEvent> ringBuffer;
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
    private final ConcurrentHashMap<String, TransactionContext> processingTxs = new ConcurrentHashMap<>();
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

        // 重置事件对象，用于Disruptor的对象重用
        public void clear() {
            this.transaction = null;
            this.future = null;
        }
    }

    /**
     * 交易上下文，用于追踪处理状态和超时
     */
    private static class TransactionContext {
        private final Transaction transaction;
        private final long submitTime;
        private final CompletableFuture<Result<String>> future;
        private final AtomicInteger retryCount = new AtomicInteger(0);
        // POH记录，用于时序验证
        private POHRecord pohRecord;

        public TransactionContext(Transaction transaction, CompletableFuture<Result<String>> future) {
            this.transaction = transaction;
            this.future = future;
            this.submitTime = System.currentTimeMillis();
        }

        public boolean isTimeout() {
            return System.currentTimeMillis() - submitTime > TIMEOUT_MILLIS;
        }

        public boolean canRetry() {
            return retryCount.incrementAndGet() <= MAX_RETRY;
        }

        public POHRecord getPohRecord() {
            return pohRecord;
        }

        public void setPohRecord(POHRecord pohRecord) {
            this.pohRecord = pohRecord;
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
        // 初始化Disruptor
        initDisruptor();

        // 初始化线程池
        initExecutors();

        // 启动定时器任务
        startScheduledTasks();

        log.info("交易池初始化完成，配置: 分片数={}, 缓冲区大小={}, 最大池大小={}",
                SHARD_COUNT, RING_BUFFER_SIZE, MAX_POOL_SIZE);
    }

    /**
     * 初始化Disruptor无锁环形缓冲区
     */
    private void initDisruptor() {
        // 事件工厂
        EventFactory<TransactionEvent> factory = TransactionEvent::new;

        // 等待策略：高吞吐量场景使用YieldingWaitStrategy
        WaitStrategy waitStrategy = new YieldingWaitStrategy();

        // 创建Disruptor
        disruptor = new Disruptor<>(factory, RING_BUFFER_SIZE,
                new ThreadFactory() {
                    private final AtomicInteger counter = new AtomicInteger(0);
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread thread = new Thread(r, "tx-disruptor-" + counter.incrementAndGet());
                        thread.setDaemon(true);
                        return thread;
                    }
                },
                ProducerType.MULTI,
                waitStrategy);

        // 设置事件处理器
        EventHandler<TransactionEvent> handler = (event, sequence, endOfBatch) -> {
            processTransactionEvent(event);
            event.clear(); // 重置事件，便于重用
        };

        disruptor.handleEventsWith(handler);

        // 启动Disruptor
        ringBuffer = disruptor.start();
    }


    /**
     * 启动定时任务
     */
    private void startScheduledTasks() {
        // 启动超时检查任务
        timeoutChecker.scheduleAtFixedRate(() -> {
            try {
                checkTimeoutTransactions();
            } catch (Exception e) {
                log.error("超时检查任务异常", e);
            }
        }, CHECK_TIMEOUT_INTERVAL, CHECK_TIMEOUT_INTERVAL, TimeUnit.MILLISECONDS);

        // 启动过期交易清理任务
        cleanupScheduler.scheduleAtFixedRate(() -> {
            try {
                cleanupExpiredTransactions();
            } catch (Exception e) {
                log.error("清理过期交易任务异常", e);
            }
        }, CLEANUP_INTERVAL, CLEANUP_INTERVAL, TimeUnit.MILLISECONDS);

        // 启动TPS统计任务
        tpsStatScheduler.scheduleAtFixedRate(() -> {
            try {
                calculateTps();
            } catch (Exception e) {
                log.error("TPS统计任务异常", e);
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    /**
     * 检查超时交易
     */
    private void checkTimeoutTransactions() {
        long currentTime = System.currentTimeMillis();
        List<String> timeoutTxIds = new ArrayList<>();

        // 收集超时交易
        processingTxs.forEach((txId, context) -> {
            if (context.isTimeout()) {
                timeoutTxIds.add(txId);
            }
        });

        // 处理超时交易
        for (String txId : timeoutTxIds) {
            TransactionContext context = processingTxs.remove(txId);
            if (context != null) {
                timeoutCount.increment();
                log.warn("交易处理超时: {}", txId);

                // 如果可以重试，重新加入处理队列
                if (context.canRetry()) {
                    log.info("交易将重试处理: {}, 重试次数: {}", txId, context.retryCount.get());
                    retryTransaction(context);
                } else {
                    // 重试次数耗尽，标记为失败
                    byte[] txIdBytes = hexToBytes(txId);
                    txStatusMap.put(txIdBytes, TransactionStatus.INVALID);
                    context.future.complete(Result.error("交易处理超时，已达到最大重试次数"));

                    // 从交易池中移除
                    removeTransaction(txIdBytes);
                }
            }
        }
    }

    /**
     * 重试处理交易
     */
    private void retryTransaction(TransactionContext context) {
        try {
            // 将交易重新加入Disruptor队列
            long sequence = ringBuffer.next();
            try {
                TransactionEvent event = ringBuffer.get(sequence);
                event.setTransaction(context.transaction);
                event.setFuture(context.future);
            } finally {
                ringBuffer.publish(sequence);
            }
        } catch (Exception e) {
            log.error("交易重试失败", e);
            context.future.complete(Result.error("交易重试失败: " + e.getMessage()));
        }
    }

    /**
     * 检查超时交易
     */
    private void checkTimeouts() {
        List<String> timeoutTxIds = new ArrayList<>();

        // 遍历处理中的交易，检查超时
        processingTxs.forEach((txId, context) -> {
            if (context.isTimeout()) {
                timeoutTxIds.add(txId);
                if (context.canRetry()) {
                    // 重试处理
                    log.warn("交易{}处理超时，进行第{}次重试", txId, context.retryCount.get());
                    processSingleTransaction(context.transaction);
                } else {
                    // 超过最大重试次数，标记为失败
                    log.error("交易{}处理超时，已达最大重试次数", txId);
                    timeoutCount.increment();
                    txStatusMap.put(context.transaction.getTxId(), TransactionStatus.INVALID);
                    context.future.complete(Result.error("交易处理超时"));
                }
            }
        });

        // 移除超时且不再重试的交易
        timeoutTxIds.forEach(processingTxs::remove);
    }

    /**
     * 处理单笔交易
     */
    private void processSingleTransaction(Transaction tx) {
        processingPool.execute(() -> {
            String txIdHex = ByteUtils.bytesToHex(tx.getTxId());
            try {
                // 标记为处理中
                txStatusMap.put(tx.getTxId(), TransactionStatus.PROCESSING);

                // 生成POH时间戳
                Transaction txWithTimestamp = pohService.generateTimestamp(tx);

                // 模拟交易处理（实际中应执行交易逻辑）
                Thread.sleep(1); // 模拟处理耗时

                // 处理成功
                processedCount.increment();
                txStatusMap.put(tx.getTxId(), TransactionStatus.CONFIRMED);
                processedTxIds.add(tx.getTxId());

                // 从交易池中移除
                removeProcessedTransactions(Collections.singletonList(txIdHex));

                log.debug("交易{}处理成功", txIdHex);
            } catch (Exception e) {
                log.error("交易{}处理失败", txIdHex, e);
                failedCount.increment();
                txStatusMap.put(tx.getTxId(), TransactionStatus.INVALID);
            } finally {
                processingTxs.remove(txIdHex);
            }
        });
    }

    /**
     * 清理过期交易
     */
    private void cleanupExpiredTransactions() {
        long currentTime = System.currentTimeMillis() / 1000; // 秒级时间
        int removedCount = 0;

        // 遍历所有分片清理过期交易
        for (Map<byte[], Transaction> shard : txShards) {
            List<byte[]> toRemove = new ArrayList<>();

            shard.forEach((txId, tx) -> {
                // 检查交易是否过期（基于最近区块哈希的有效期）
                //TODO


                toRemove.add(txId);
            });

            // 批量移除过期交易
            for (byte[] txId : toRemove) {
                if (shard.remove(txId) != null) {
                    removedCount++;
                    poolSize.decrementAndGet();
                    txStatusMap.remove(txId);
                    processedTxIds.remove(txId);
                    // 同时从交易组中移除

                }
            }
        }

        if (removedCount > 0) {
            log.info("清理过期交易完成，共移除 {} 笔交易", removedCount);
        }
    }

    /**
     * 计算当前TPS
     */
    private void calculateTps() {
        long currentTime = System.currentTimeMillis();
        long currentProcessed = processedCount.sum();

        // 计算时间差（秒）
        long timeDiff = (currentTime - lastStatTime) / 1000;
        if (timeDiff <= 0) {
            return;
        }

        // 计算TPS
        long tps = (currentProcessed - lastProcessedCount) / timeDiff;
        currentTps.set(tps);

        // 更新统计基准
        lastStatTime = currentTime;
        lastProcessedCount = currentProcessed;

        // 定期打印统计信息
/*        if (currentProcessed % 10000 == 0) {
            log.info("交易池统计 - TPS: {}, 总提交: {}, 总处理: {}, 总失败: {}, 总超时: {}, 当前池大小: {}",
                    tps,
                    submittedCount.sum(),
                    processedCount.sum(),
                    failedCount.sum(),
                    timeoutCount.sum(),
                    poolSize.get());
        }*/
    }


    /**
     * 初始化线程池
     */
    private void initExecutors() {
        // 提交线程池：处理交易提交的轻量任务
        submitPool = new ThreadPoolExecutor(
                PROCESS_THREAD_COUNT,
                PROCESS_THREAD_COUNT,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(QUEUE_CAPACITY / 2),
                new ThreadFactory() {
                    private final AtomicInteger counter = new AtomicInteger(0);
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread thread = new Thread(r, "tx-submit-" + counter.incrementAndGet());
                        thread.setDaemon(true);
                        return thread;
                    }
                },
                new ThreadPoolExecutor.CallerRunsPolicy() // 背压策略：让提交者等待
        );

        // 处理线程池：处理交易验证和执行的重量级任务
        processPool = new ForkJoinPool(
                PROCESS_THREAD_COUNT,
                ForkJoinPool.defaultForkJoinWorkerThreadFactory,
                (t, e) -> log.error("交易处理线程异常", e),
                true
        );

        // 超时检查定时器
        timeoutChecker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "tx-timeout-checker");
            thread.setDaemon(true);
            return thread;
        });

        // 清理过期交易定时器
        cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "tx-cleanup-scheduler");
            thread.setDaemon(true);
            return thread;
        });

        // TPS统计定时器
        tpsStatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "tx-tps-stat");
            thread.setDaemon(true);
            return thread;
        });
    }


    @Override
    public CompletableFuture<Boolean> submitTransaction(Transaction transaction) {
        return null;
    }

    @Override
    public List<CompletableFuture<Boolean>> batchSubmitTransactions(List<Transaction> transactions) {
        return List.of();
    }

    @Override
    public List<Transaction> getPendingTransactions(int maxCount) {
        return List.of();
    }

    @Override
    public void removeProcessedTransactions(List<String> transactionIds) {

    }

    @Override
    public void removeTransactions(List<TransactionHash> transactionIds) {

    }


    @Override
    public int getPoolSize() {
        return 0;
    }

    @Override
    public boolean validateTransaction(Transaction transaction) {
        return false;
    }

    @Override
    public Result getTxPool() {
        // 构建交易池状态信息
        Map<String, Object> poolInfo = new HashMap<>();
        poolInfo.put("totalSize", poolSize.get());
        poolInfo.put("maxSize", MAX_POOL_SIZE);
        poolInfo.put("shards", SHARD_COUNT);
        poolInfo.put("currentTps", currentTps.get());
        poolInfo.put("queuedTransactions", submitQueue.size());
        poolInfo.put("processingTransactions", processingTxs.size());

        // 添加各状态交易数量
        Map<TransactionStatus, Integer> statusCount = new EnumMap<>(TransactionStatus.class);
        for (TransactionStatus status : TransactionStatus.values()) {
            statusCount.put(status, 0);
        }

        txStatusMap.values().forEach(status ->
                statusCount.put(status, statusCount.get(status) + 1)
        );
        poolInfo.put("statusCount", statusCount);

        return Result.OK(poolInfo);
    }

    @Override
    public TransactionStatus getStatus(byte[] txId) {
        if (txId == null) {
            return null;
        }
        try {
            return txStatusMap.get(txId);
        } catch (Exception e) {
            log.error("获取交易状态失败, txId: {}", txId, e);
            return null;
        }
    }

    @Override
    public Result getTxPoolStatus() {
        StringBuilder status = new StringBuilder();
        status.append("交易池状态:\n");
        status.append("  当前大小: ").append(poolSize.get()).append("/").append(MAX_POOL_SIZE).append("\n");
        status.append("  当前TPS: ").append(currentTps.get()).append("\n");
        status.append("  处理中交易: ").append(processingTxs.size()).append("\n");
        status.append("  等待队列: ").append(submitQueue.size()).append("\n");
        status.append("  统计信息: \n");
        status.append("    总提交: ").append(submittedCount.sum()).append("\n");
        status.append("    总处理成功: ").append(processedCount.sum()).append("\n");
        status.append("    总失败: ").append(failedCount.sum()).append("\n");
        status.append("    总超时: ").append(timeoutCount.sum()).append("\n");
        status.append("  缓存统计: \n");
        status.append("    签名缓存命中率: ").append(String.format("%.2f%%",
                calculateCacheHitRate())).append("\n");
        return Result.OK(status.toString());
    }

    /**
     * 计算签名缓存命中率
     */
    private double calculateCacheHitRate() {
        CacheStats stats = signatureCache.stats();
        long requests = stats.requestCount();
        if (requests == 0) {
            return 0.0;
        }
        return (double) stats.hitCount() / requests * 100;
    }

    /**
     * 异步验证 + 预验证
     * 轻量预验证（格式、签名合法性）在addTransaction时同步快速完成，过滤明显无效的交易。
     * 重量级验证（余额检查、双花检测）交给线程池异步执行，验证通过后再正式加入交易池。
     */
    @Override
    public Result<String> verifyTransaction(Transaction tx)  {
        // 1. 轻量预验证（同步，快速失败）
        Result<String> preCheck = preVerify(tx);
        if (!preCheck.isSuccess()) {
            return preCheck;
        }
        // 2. 异步执行重量级验证
        CompletableFuture<Result<String>> future = new CompletableFuture<>();
        try {
            // 提交到Disruptor进行异步处理
            long sequence = ringBuffer.next();
            try {
                TransactionEvent event = ringBuffer.get(sequence);
                event.setTransaction(tx);
                event.setFuture(future);
            } finally {
                ringBuffer.publish(sequence);
            }
            // 等待验证结果，但设置超时
            return future.get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return Result.error("交易验证超时");
        } catch (Exception e) {
            return Result.error("交易验证失败: " + e.getMessage());
        }
    }


    /**
     * 处理Disruptor中的交易事件
     */
    private void processTransactionEvent(TransactionEvent event) {
        Transaction tx = event.getTransaction();
        CompletableFuture<Result<String>> future = event.getFuture();

        if (tx == null || future == null) {
            return;
        }

        try {
            // 执行重量级验证
            Result<String> heavyCheck = fullVerify(tx);
            if (!heavyCheck.isSuccess()) {
                future.complete(heavyCheck);
                failedCount.increment();
                return;
            }

            // 验证通过，添加到交易池
            Result<String> addResult = addTransactionToPool(tx);
            future.complete(addResult);
        } catch (Exception e) {
            log.error("处理交易事件失败", e);
            future.complete(Result.error("交易处理异常: " + e.getMessage()));
            failedCount.increment();
        }
    }



    /**
     * 带超时控制的添加交易
     */
    @Override
    public Result<String> addTransaction(Transaction tx) {
        // 1. 预验证
        Result<String> preCheck = preVerify(tx);
        if (!preCheck.isSuccess()) {
            return preCheck;
        }

        try {
            // 2. 尝试加入提交队列（带超时）
            boolean added = submitQueue.offer(tx, 100, TimeUnit.MILLISECONDS);
            if (!added) {
                return Result.error("交易提交队列已满，请稍后再试");
            }

            // 3. 提交计数器增加
            submittedCount.increment();

            // 4. 异步处理队列中的交易
            submitPool.execute(this::processSubmitQueue);

            return Result.OK("交易已接受，等待处理");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.error("交易提交被中断");
        } catch (Exception e) {
            log.error("添加交易失败", e);
            return Result.error("添加交易失败: " + e.getMessage());
        }
    }

    /**
     * 处理提交队列中的交易
     */
    private void processSubmitQueue() {
        try {
            Transaction tx;
            // 循环处理队列中的交易，直到队列为空
            while ((tx = submitQueue.poll()) != null) {
                // 执行重量级验证
                Result<String> verifyResult = fullVerify(tx);
                if (!verifyResult.isSuccess()) {
                    log.warn("交易验证失败: {}", verifyResult.getMessage());
                    failedCount.increment();
                    continue;
                }

                // 验证通过，添加到交易池
                Result<String> addResult = addTransactionToPool(tx);
                if (!addResult.isSuccess()) {
                    log.warn("添加交易到池失败: {}", addResult.getMessage());
                    failedCount.increment();
                }
            }
        } catch (Exception e) {
            log.error("处理提交队列异常", e);
        }
    }

    /**
     * 将交易添加到交易池
     */
    private Result<String> addTransactionToPool(Transaction tx) {
        try {
            // 检查交易池是否已满
            if (poolSize.get() >= MAX_POOL_SIZE) {
                return Result.error("交易池已满");
            }

            byte[] txId = tx.getTxId();
            String txIdHex = bytesToHex(txId);

            // 检查是否已存在相同交易
            if (processedTxIds.contains(txId) || txMap.containsKey(txId)) {
                return Result.error("交易已存在，可能是双花尝试");
            }

            // 获取发送者账户（费用支付者）
            byte[] sender = tx.getSender();
            if (sender == null) {
                return Result.error("无法确定交易发送者");
            }

            // 确定分片索引
            int shardIndex = getShardIndex(txId);
            Map<byte[], Transaction> txShard = txShards.get(shardIndex);
            int groupShardIndex = getShardIndex(sender);
            ConcurrentMap<byte[], TransactionGroup> groupShard = groupShards.get(groupShardIndex);

            // 添加到交易分片
            txShard.put(txId, tx);
            txMap.put(txId, tx);

            // 添加到交易组
            groupShard.compute(sender, (key, group) -> {
                if (group == null) {
                    group = new TransactionGroup(sender);
                }
                group.getTransactions().add(tx);
                return group;
            });

            // 更新交易状态
            txStatusMap.put(txId, TransactionStatus.PENDING);

            // 增加交易池计数
            poolSize.incrementAndGet();

            // 更新账户冲突布隆过滤器
            updateAccountConflictBloom(tx);

            log.debug("交易已添加到池: {}", txIdHex);
            return Result.OK(txIdHex);
        } catch (Exception e) {
            log.error("添加交易到池失败", e);
            return Result.error("添加交易到池失败: " + e.getMessage());
        }
    }


    /**
     * 从交易池中移除交易
     */
    private boolean removeTransaction(byte[] txId) {
        if (txId == null) {
            return false;
        }

        // 从主映射中移除
        Transaction tx = txMap.remove(txId);
        if (tx == null) {
            return false;
        }

        // 从分片中移除
        int shardIndex = getShardIndex(txId);
        txShards.get(shardIndex).remove(txId);

        // 从状态映射中移除
        txStatusMap.remove(txId);

        // 减少计数器
        poolSize.decrementAndGet();

        // 从交易组中移除
        if (tx.getSender() != null) {
            removeTransactionFromGroup(tx.getSender(), txId);
        }

        return true;
    }

    /**
     * 从交易组中移除交易
     */
    private void removeTransactionFromGroup(byte[] groupId, byte[] txId) {
        int groupShardIndex = getShardIndex(groupId);
        ConcurrentMap<byte[], TransactionGroup> groupShard = groupShards.get(groupShardIndex);

        groupShard.computeIfPresent(groupId, (key, group) -> {
            List<Transaction> txs = group.getTransactions();
            txs.removeIf(tx -> Arrays.equals(tx.getTxId(), txId));

            // 如果交易组为空，移除该组
            return txs.isEmpty() ? null : group;
        });
    }


    /**
     * 更新账户冲突布隆过滤器
     */
    private void updateAccountConflictBloom(Transaction tx) {
        if (tx.getAccounts() != null) {
            for (AccountMeta account : tx.getAccounts()) {
                if (account.isWritable()) {
                    // 对可写账户添加到布隆过滤器，用于快速检测潜在冲突
                    accountConflictBloom.add(account.getPublicKey());
                }
            }
        }
    }

    /**
     * 内部处理交易的逻辑
     */
    private boolean processTransactionInternal(Transaction tx) {
        try {
            // 1. 再次验证签名（双重检查）
            boolean signatureValid = verifyTransactionSignature(tx);
            if (!signatureValid) {
                log.warn("交易签名验证失败: {}", bytesToHex(tx.getTxId()));
                return false;
            }

            // 2. 检查账户余额是否充足（这里只是示例，实际需要查询区块链状态）
            boolean hasEnoughBalance = checkAccountBalance(tx);
            if (!hasEnoughBalance) {
                log.warn("账户余额不足: {}", bytesToHex(tx.getSender()));
                return false;
            }

            // 3. 检查交易是否已过期
            if (isTransactionExpired(tx)) {
                log.warn("交易已过期: {}", bytesToHex(tx.getTxId()));
                return false;
            }

            // 4. 执行其他必要的业务逻辑验证
            // ...

            // 5. 交易处理完成，准备打包到区块
            // 这里可以添加到区块构建队列等操作

            return true;
        } catch (Exception e) {
            log.error("交易内部处理失败", e);
            return false;
        }
    }

    private boolean verifyTransactionSignature(Transaction tx) {
        return true;
    }

    /**
     * 检查交易是否已过期
     */
    private boolean isTransactionExpired(Transaction tx) {
        if (tx.getRecentBlockhash() == null) {
            return true; // 没有最近区块哈希，视为过期
        }
        long currentTime = System.currentTimeMillis() / 1000; // 秒级时间


        return false;
    }



    /**
     * 检查账户余额是否充足
     */
    private boolean checkAccountBalance(Transaction tx) {
        // 实际实现中需要查询区块链状态获取账户余额
        // 这里简化处理，假设余额充足
        return true;
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

        if (groups.isEmpty()) {
            return;
        }
        log.debug("开始处理交易组，共 {} 个组", groups.size());
        // 2. 并行处理无冲突的交易组
        groups.parallelStream().forEach(group -> {
            // 检查组是否仍存在（可能已被清理）
            int groupShardIndex = getShardIndex(group.getGroupId());
            ConcurrentMap<byte[], TransactionGroup> groupShard = groupShards.get(groupShardIndex);

            if (groupShard.containsKey(group.getGroupId())) {
                processGroup(group);
            }
        });
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
