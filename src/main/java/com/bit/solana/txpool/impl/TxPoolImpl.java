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
    // 单个交易处理超时时间（200ms）
    private static final long TIMEOUT_MILLIS = 200;
    // 超时检查间隔（100ms）
    private static final long CHECK_TIMEOUT_INTERVAL = 100;
    // 最大交易池大小，防止内存溢出
    private static final int MAX_POOL_SIZE = 1_000_000;
    // 清理过期交易的间隔时间
    private static final long CLEANUP_INTERVAL = 5_000;
    // 交易过期时间（秒）- 基于recentBlockhash的有效性
    private static final long TX_EXPIRE_SECONDS = 120;


    // ==================== 核心组件 ====================
    @Autowired
    private POHService pohService;

    // 处理线程池（工作窃取算法）
    private ForkJoinPool processPool;
    // 超时检查定时器
    private ScheduledExecutorService timeoutChecker;
    // 清理过期交易定时器
    private ScheduledExecutorService cleanupScheduler;
    // TPS统计定时器
    private ScheduledExecutorService tpsStatScheduler;


    // 交易分组（groupId -> 交易组，groupId通常为sender账户）
    private final ConcurrentMap<byte[], TransactionGroup> txGroups = new ConcurrentHashMap<>();

    // ==================== 数据存储 ====================

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





    @PostConstruct
    public void init(){
        // 初始化分片

        // 初始化线程池
        initExecutors();
        // 启动定时器任务
        startScheduledTasks();

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

    }



    /**
     * 检查超时交易
     */
    private void checkTimeouts() {

    }

    /**
     * 处理单笔交易
     */
    private void processSingleTransaction(Transaction tx) {

    }

    /**
     * 清理过期交易
     */
    private void cleanupExpiredTransactions() {

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


    /**
     *
     * @param transaction 待提交交易
     * @return
     */

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
        return true;
    }

    @Override
    public Result getTxPool() {
        // 构建交易池状态信息
        Map<String, Object> poolInfo = new HashMap<>();
        poolInfo.put("totalSize", poolSize.get());
        poolInfo.put("maxSize", MAX_POOL_SIZE);
        poolInfo.put("currentTps", currentTps.get());
        poolInfo.put("processingTransactions", processingTxs.size());




        return Result.OK(poolInfo);
    }

    @Override
    public short getStatus(byte[] txId) {

        return 0;
    }

    @Override
    public Result getTxPoolStatus() {
        StringBuilder status = new StringBuilder();
        status.append("交易池状态:\n");
        status.append("  当前大小: ").append(poolSize.get()).append("/").append(MAX_POOL_SIZE).append("\n");
        status.append("  当前TPS: ").append(currentTps.get()).append("\n");
        status.append("  处理中交易: ").append(processingTxs.size()).append("\n");
        status.append("  统计信息: \n");
        status.append("    总提交: ").append(submittedCount.sum()).append("\n");
        status.append("    总处理成功: ").append(processedCount.sum()).append("\n");
        status.append("    总失败: ").append(failedCount.sum()).append("\n");
        status.append("    总超时: ").append(timeoutCount.sum()).append("\n");
        return Result.OK(status.toString());
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




            // 等待验证结果，但设置超时
            return future.get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return Result.error("交易验证超时");
        } catch (Exception e) {
            return Result.error("交易验证失败: " + e.getMessage());
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



            // 3. 提交计数器增加
            submittedCount.increment();



            return Result.OK("交易已接受，等待处理");
        } catch (Exception e) {
            log.error("添加交易失败", e);
            return Result.error("添加交易失败: " + e.getMessage());
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


            // 获取发送者账户（费用支付者）
            byte[] sender = tx.getSender();
            if (sender == null) {
                return Result.error("无法确定交易发送者");
            }




            // 添加到交易组


            // 更新交易状态


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

        return true;
    }

    /**
     * 从交易组中移除交易
     */
    private void removeTransactionFromGroup(byte[] groupId, byte[] txId) {

    }


    /**
     * 更新账户冲突布隆过滤器
     */
    private void updateAccountConflictBloom(Transaction tx) {

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

        // 关闭线程池
        if (processPool != null) {
            processPool.shutdown();
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
