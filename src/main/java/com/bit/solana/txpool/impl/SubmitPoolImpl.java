package com.bit.solana.txpool.impl;

import com.bit.solana.structure.tx.Transaction;
import com.bit.solana.txpool.SubmitPool;
import com.google.common.hash.Hashing;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.StampedLock;
import java.util.stream.Collectors;

@Slf4j
@Component
public class SubmitPoolImpl implements SubmitPool {
    // 配置参数（根据实际压测调整）
    private static final int MAX_CAPACITY = 2 << 19; // 1048576 笔
    private static final int MAX_SIZE = 2 << 29;      // 1G 字节
    private static final int SELECTION_SIZE = 5_000;  // 每次筛选最大数量
    private static final int SHARD_COUNT = 32;        // 分片数（2的幂次，增加分片减少竞争）
    private static final int SEGMENT_CAPACITY = MAX_CAPACITY / SHARD_COUNT; // 每分片容量
    private static final int SEGMENT_SIZE = MAX_SIZE / SHARD_COUNT;         // 每分片字节限制

    // 全局统计（原子操作确保精确性）
    private final AtomicInteger totalTx = new AtomicInteger(0);
    private final AtomicLong totalBytes = new AtomicLong(0);

    // 分片数组：使用ConcurrentSkipListSet实现无锁排序，StampedLock优化读写
    private final Shard[] shards = new Shard[SHARD_COUNT];

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

        // 缓存交易大小（避免重复计算）
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
            return true;
        } else {
            // 添加失败（重复）：回滚容量计数
            shard.bytes.addAndGet(-txSize);
            totalBytes.addAndGet(-txSize);
            log.debug("Duplicate transaction {} found in shard {}", transaction.getTxIdStr(), shardIndex);
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

        log.info("Total expired transactions removed: {}", totalRemoved);
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
}