package com.bit.solana.txpool.impl;

import com.bit.solana.structure.tx.Transaction;
import com.bit.solana.structure.tx.TransactionStatusResolver;
import com.bit.solana.txpool.SubmitPool;
import com.google.common.hash.Hashing;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.StampedLock;
import java.util.stream.Collectors;

@Slf4j
@Component
public class SubmitPoolImpl implements SubmitPool {
    // 配置参数（根据实际压测调整）
    private static final int MAX_CAPACITY = 1 << 20; // 1048576 笔
    private static final int MAX_SIZE = 1 << 30;      // 1G 字节
    private static final int SELECTION_SIZE = 1<<12;  // 每次筛选最大数量
    private static final int SHARD_COUNT = 32;        // 分片数（2的幂次，增加分片减少竞争）
    private static final int SEGMENT_CAPACITY = MAX_CAPACITY / SHARD_COUNT; // 每分片容量
    private static final int SEGMENT_SIZE = MAX_SIZE / SHARD_COUNT;         // 每分片字节限制
    // 交易过期时间（ms）
    private static final long TX_EXPIRE_SECONDS = 400;

    // 全局统计（原子操作确保精确性）
    private final AtomicInteger totalTx = new AtomicInteger(0);
    private final AtomicLong totalBytes = new AtomicLong(0);

    // 分片数组：使用ConcurrentSkipListSet实现无锁排序，StampedLock优化读写
    private final Shard[] shards = new Shard[SHARD_COUNT];

    // 清理过期交易定时器
    private ThreadPoolTaskScheduler cleanupScheduler;

    // 分片内部结构
    private static class Shard {
        // 按交易费用降序排序，支持并发操作
        final ConcurrentSkipListSet<Transaction> txSet;
        // 读写锁：乐观读+悲观写，减少锁竞争
        final StampedLock lock = new StampedLock();
        // 分片内统计（原子操作避免锁内计算）
        final AtomicInteger count = new AtomicInteger(0);
        final AtomicLong bytes = new AtomicLong(0);

        Shard(Comparator<Transaction> comparator) {
            this.txSet = new ConcurrentSkipListSet<>(comparator);
        }
    }

    @PostConstruct
    public void init() {
        // 按交易费用降序排序（高优先级优先），同时重写equals避免重复（基于txId）
        Comparator<Transaction> comparator = Comparator.comparingLong(Transaction::getFee).reversed()
                .thenComparing(Transaction::getTxIdStr); // 相同费用按txId去重
        for (int i = 0; i < SHARD_COUNT; i++) {
            shards[i] = new Shard(comparator);
        }
        log.info("SubmitPool initialized with {} shards, max capacity: {} txs, max size: {} bytes",
                SHARD_COUNT, MAX_CAPACITY, MAX_SIZE);
        // 初始化定时清理调度器
        initCleanupScheduler();
    }

    /**
     * 初始化定时清理任务调度器，每400ms执行一次过期交易清理
     */
    private void initCleanupScheduler() {
        cleanupScheduler = new ThreadPoolTaskScheduler();
        cleanupScheduler.setThreadNamePrefix("tx-cleanup-"); // 线程名前缀，便于日志跟踪
        cleanupScheduler.setPoolSize(1); // 清理任务单线程即可，避免并发冲突
        cleanupScheduler.initialize();

        // 提交定时任务：每400ms执行一次
        cleanupScheduler.scheduleAtFixedRate(
                this::cleanExpiredTask, // 执行的任务
                400 // 间隔时间（ms）
        );
        log.info("Expired transaction cleanup scheduler initialized, interval: 400ms");
    }

    /**
     * 定时清理任务的具体逻辑
     */
    private void cleanExpiredTask() {
        try {
            long currentTime = System.currentTimeMillis();
            int removedCount = cleanExpiredTransactions(currentTime);
            if (removedCount > 0) {
                log.debug("Cleanup task removed {} expired transactions", removedCount);
            }
        } catch (Exception e) {
            log.error("Error occurred during expired transaction cleanup", e);
        }
    }

    /**
     * 优化哈希算法：使用MurmurHash确保分片均匀分布
     */
    private int getShardIndex(Transaction tx) {
        String txId = tx.getTxIdStr();
        int hash = Hashing.murmur3_32_fixed().hashString(txId, StandardCharsets.UTF_8).asInt();
        return Math.abs(hash) % SHARD_COUNT;
    }

    /**
     * 提取并删除优先级最高的交易（批量操作优化）
     */
    @Override
    public List<Transaction> selectAndRemoveTopTransactions() {
        List<Transaction> result = new ArrayList<>(SELECTION_SIZE);
        int remaining = SELECTION_SIZE;

        // 轮询分片提取交易，减少单分片压力
        for (Shard shard : shards) {
            if (remaining <= 0) break;

            long stamp = shard.lock.tryOptimisticRead(); // 乐观读尝试
            List<Transaction> toRemove = new ArrayList<>();
            try {
                // 读取前N个高优先级交易
                Iterator<Transaction> iterator = shard.txSet.iterator();
                while (remaining > 0 && iterator.hasNext()) {
                    toRemove.add(iterator.next());
                    remaining--;
                }
                // 验证乐观读是否有效（无写入干扰）
                if (!shard.lock.validate(stamp)) {
                    stamp = shard.lock.readLock(); // 升级为悲观读锁
                    try {
                        iterator = shard.txSet.iterator();
                        toRemove.clear();
                        while (remaining > 0 && iterator.hasNext()) {
                            toRemove.add(iterator.next());
                            remaining--;
                        }
                    } finally {
                        shard.lock.unlockRead(stamp);
                    }
                }
            } finally {
                // 批量删除（悲观写锁）
                if (!toRemove.isEmpty()) {
                    stamp = shard.lock.writeLock();
                    try {
                        int removed = 0;
                        long bytesRemoved = 0;
                        for (Transaction tx : toRemove) {
                            if (shard.txSet.remove(tx)) { // 确保存在再删除
                                removed++;
                                bytesRemoved += tx.getSize(); // 使用缓存的大小
                            }
                        }
                        // 批量更新统计（减少原子操作次数）
                        if (removed > 0) {
                            shard.count.addAndGet(-removed);
                            shard.bytes.addAndGet(-bytesRemoved);
                            totalTx.addAndGet(-removed);
                            totalBytes.addAndGet(-bytesRemoved);
                            result.addAll(toRemove.subList(0, removed));
                        }
                    } finally {
                        shard.lock.unlockWrite(stamp);
                    }
                }
            }
        }

        log.debug("Selected {} top transactions from pool", result.size());
        return result;
    }

    /**
     * 添加交易（原子化容量控制+无锁判断）
     */
    @Override
    public boolean addTransaction(Transaction transaction) {
        if (transaction == null) {
            log.warn("Attempt to add null transaction");
            return false;
        }
        //轻度校验快速失败 TODO
        TransactionStatusResolver.addStatus(transaction, TransactionStatusResolver.UNSUBMITTED);
        transaction.setSubmitTime(System.currentTimeMillis());
        int txSize = transaction.getSize();
        int shardIndex = getShardIndex(transaction);
        Shard shard = shards[shardIndex];

        // 1. 原子判断全局容量（先判断再尝试添加，减少锁竞争）
        if (totalTx.get() >= MAX_CAPACITY || totalBytes.addAndGet(txSize) > MAX_SIZE) {
            totalBytes.addAndGet(-txSize); // 回滚
            log.debug("Global capacity exceeded, cannot add transaction {}", transaction.getTxIdStr());
            return false;
        }

        // 2. 原子判断分片容量
        if (shard.count.get() >= SEGMENT_CAPACITY || shard.bytes.addAndGet(txSize) > SEGMENT_SIZE) {
            shard.bytes.addAndGet(-txSize); // 回滚
            totalBytes.addAndGet(-txSize);  // 回滚全局
            log.debug("Shard {} capacity exceeded, cannot add transaction {}", shardIndex, transaction.getTxIdStr());
            return false;
        }

        // 3. 无锁添加（ConcurrentSkipListSet的add是原子操作）
        boolean added = shard.txSet.add(transaction);
        if (added) {
            // 添加成功：更新计数
            shard.count.incrementAndGet();
            totalTx.incrementAndGet();
            log.trace("Added transaction {} to shard {}, current shard count: {}",
                    transaction.getTxIdStr(), shardIndex, shard.count.get());
            TransactionStatusResolver.addStatus(transaction, TransactionStatusResolver.SUBMITTED);
            return true;
        } else {
            // 添加失败（重复）：回滚容量计数
            shard.bytes.addAndGet(-txSize);
            totalBytes.addAndGet(-txSize);
            log.debug("Duplicate transaction {} found in shard {}", transaction.getTxIdStr(), shardIndex);
            TransactionStatusResolver.addStatus(transaction, TransactionStatusResolver.DROPPED);
            return false;
        }
    }

    /**
     * 清除过期交易（批量扫描+乐观读）
     */
    @Override
    public int cleanExpiredTransactions(long currentTime) {
        int totalRemoved = 0;

        for (int i = 0; i < SHARD_COUNT; i++) {
            Shard shard = shards[i];
            long stamp = shard.lock.tryOptimisticRead();
            List<Transaction> expired = new ArrayList<>();

            try {
                // 乐观读扫描过期交易
                List<Transaction> finalExpired = expired;
                shard.txSet.forEach(tx -> {
                    if (tx.isExpired(currentTime)) {
                        finalExpired.add(tx);
                    }
                });
                // 验证乐观读有效性
                if (!shard.lock.validate(stamp)) {
                    stamp = shard.lock.readLock();
                    try {
                        expired = shard.txSet.stream()
                                .filter(tx -> tx.isExpired(currentTime))
                                .collect(Collectors.toList());
                    } finally {
                        shard.lock.unlockRead(stamp);
                    }
                }
            } finally {
                // 批量删除过期交易
                if (!expired.isEmpty()) {
                    stamp = shard.lock.writeLock();
                    try {
                        int removed = 0;
                        long bytesRemoved = 0;
                        for (Transaction tx : expired) {
                            if (shard.txSet.remove(tx)) {
                                removed++;
                                bytesRemoved += tx.getSize();
                            }
                        }
                        if (removed > 0) {
                            shard.count.addAndGet(-removed);
                            shard.bytes.addAndGet(-bytesRemoved);
                            totalTx.addAndGet(-removed);
                            totalBytes.addAndGet(-bytesRemoved);
                            totalRemoved += removed;
                        }
                    } finally {
                        shard.lock.unlockWrite(stamp);
                    }
                }
            }

            if (!expired.isEmpty()) {
                log.debug("Shard {} removed {} expired transactions", i, expired.size());
            }
        }

        log.debug("Total expired transactions removed: {}", totalRemoved);
        return totalRemoved;
    }

    @Override
    public long getTotalTransactionSize() {
        return totalBytes.get();
    }

    @Override
    public int getTotalTransactionCount() {
        return totalTx.get();
    }


    /**
     * 根据交易ID查找交易
     * @param txId 交易ID字符串
     * @return 找到的交易，未找到则返回null
     */
    @Override
    public Transaction findTransactionByTxId(String txId) {
        if (txId == null || txId.isEmpty()) {
            log.warn("查找交易失败：交易ID为空");
            return null;
        }

        // 计算目标分片索引（复用哈希算法）
        int shardIndex = getShardIndexByTxId(txId);
        Shard shard = shards[shardIndex];

        long stamp = shard.lock.tryOptimisticRead();
        try {
            // 遍历分片内交易，匹配txId
            for (Transaction tx : shard.txSet) {
                if (txId.equals(tx.getTxIdStr())) {
                    return tx;
                }
            }
            // 验证乐观读有效性，无效则升级为悲观读
            if (!shard.lock.validate(stamp)) {
                stamp = shard.lock.readLock();
                try {
                    for (Transaction tx : shard.txSet) {
                        if (txId.equals(tx.getTxIdStr())) {
                            return tx;
                        }
                    }
                } finally {
                    shard.lock.unlockRead(stamp);
                }
            }
        } catch (Exception e) {
            log.error("查找交易ID[{}]失败", txId, e);
        }
        return null;
    }

    /**
     * 根据交易ID删除交易
     * @param txId 交易ID字符串
     * @return 成功删除返回true，否则返回false
     */
    @Override
    public boolean removeTransactionByTxId(String txId) {
        if (txId == null || txId.isEmpty()) {
            log.warn("删除交易失败：交易ID为空");
            return false;
        }

        // 计算目标分片索引（复用哈希算法）
        int shardIndex = getShardIndexByTxId(txId);
        Shard shard = shards[shardIndex];

        // 先尝试乐观读定位交易
        long stamp = shard.lock.tryOptimisticRead();
        Transaction targetTx = null;
        try {
            for (Transaction tx : shard.txSet) {
                if (txId.equals(tx.getTxIdStr())) {
                    targetTx = tx;
                    break;
                }
            }
            // 验证乐观读有效性，无效则升级为悲观读重新查找
            if (!shard.lock.validate(stamp)) {
                stamp = shard.lock.readLock();
                try {
                    for (Transaction tx : shard.txSet) {
                        if (txId.equals(tx.getTxIdStr())) {
                            targetTx = tx;
                            break;
                        }
                    }
                } finally {
                    shard.lock.unlockRead(stamp);
                }
            }
        } catch (Exception e) {
            log.error("查找待删除交易ID[{}]失败", txId, e);
            return false;
        }

        // 若找到交易，执行删除（需悲观写锁）
        if (targetTx != null) {
            stamp = shard.lock.writeLock();
            try {
                // 再次检查交易是否存在（防止并发删除）
                if (shard.txSet.remove(targetTx)) {
                    int txSize = targetTx.getSize();
                    // 更新分片和全局统计
                    shard.count.decrementAndGet();
                    shard.bytes.addAndGet(-txSize);
                    totalTx.decrementAndGet();
                    totalBytes.addAndGet(-txSize);
                    log.debug("删除交易成功，ID:{}，分片:{}", txId, shardIndex);
                    return true;
                }
            } finally {
                shard.lock.unlockWrite(stamp);
            }
        }

        log.debug("未找到交易或已被删除，ID:{}，分片:{}", txId, shardIndex);
        return false;
    }

    /**
     * 根据交易ID字符串计算分片索引（复用MurmurHash算法）
     */
    private int getShardIndexByTxId(String txId) {
        int hash = Hashing.murmur3_32_fixed().hashString(txId, StandardCharsets.UTF_8).asInt();
        return Math.abs(hash) % SHARD_COUNT;
    }

    /**
     * 销毁Bean时关闭调度器，释放资源
     */
    @PreDestroy
    public void destroy() {
        if (cleanupScheduler != null) {
            cleanupScheduler.shutdown();
            log.info("Expired transaction cleanup scheduler shutdown");
        }
        // 若有其他调度器（如tpsStatScheduler），也在此处关闭
    }

}