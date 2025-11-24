package com.bit.solana.poh.impl;

import com.bit.solana.blockchain.BlockChain;
import com.bit.solana.poh.POHEngine;
import com.bit.solana.structure.poh.POHRecord;
import com.bit.solana.result.Result;
import com.bit.solana.structure.dto.POHVerificationResult;
import com.bit.solana.structure.poh.PohEventType;
import com.bit.solana.structure.tx.Transaction;
import com.bit.solana.util.Sha;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import static com.bit.solana.common.ByteHash32.HASH_LENGTH;
import static com.bit.solana.util.ByteUtils.*;


@Slf4j
@Component
public class POHEngineImpl implements POHEngine {

    /**
     * 缓存配置：使用Caffeine实现LRU策略，缓存1<<20（1,048,576）条最新的POH记录
     * 键：Tick序号（sequenceNumber）
     * 值：对应的POHRecord
     */
    private static final int CACHE_SIZE = 1 << 20; // 1,048,576条
    private  Cache<Long, POHRecord> pohCache;


    /**
     * 核心配置参数
     */
    private static final int TICKS_PER_SLOT = 64;          // 每个slot包含的tick数量
    private static final int HASHES_PER_TICK = 12_500;     // 每个tick包含的哈希计算次数
    // 移除目标耗时相关配置，不再强制控制时间

    /**
     * 全局静态变量存储核心状态
     */
    private static class GlobalState {
        private static final byte[] lastHash = new byte[HASH_LENGTH];
        private static final AtomicLong currentTick = new AtomicLong(0);
        private static final AtomicLong currentSlot = new AtomicLong(0);
        private static final Object lock = new Object();

        static {
            Arrays.fill(lastHash, (byte) 0);
        }

        static byte[] getLastHash() {
            synchronized (lock) {
                return lastHash.clone();
            }
        }

        static void setLastHash(byte[] newHash) {
            if (newHash.length != HASH_LENGTH) {
                throw new IllegalArgumentException("哈希长度必须为32字节");
            }
            synchronized (lock) {
                System.arraycopy(newHash, 0, lastHash, 0, HASH_LENGTH);
            }
        }

        static long getCurrentTick() {
            return currentTick.get();
        }

        static long incrementTick() {
            return currentTick.incrementAndGet();
        }

        static long getCurrentSlot() {
            return currentSlot.get();
        }

        static long incrementSlot() {
            return currentSlot.incrementAndGet();
        }

        static void initialize(long slot, long tick, byte[] hash) {
            synchronized (lock) {
                currentSlot.set(slot);
                currentTick.set(tick);
                if (hash != null) {
                    System.arraycopy(hash, 0, lastHash, 0, HASH_LENGTH);
                }
            }
        }

        static Object getLock() {
            return lock;
        }
    }

    private static final byte[] EMPTY_EVENT_HASH;

    static {
        EMPTY_EVENT_HASH = new byte[HASH_LENGTH];
        Arrays.fill(EMPTY_EVENT_HASH, (byte) 0);
    }

    @Autowired
    private BlockChain chain;

    private final byte[] nodeId = new byte[32];
    private ExecutorService blockProcessor;
    private static final int BATCH_MAX_SIZE = 1000;
    private static final int PROCESSOR_THREADS = Math.max(2, Runtime.getRuntime().availableProcessors());

    private volatile boolean isRunning = false;
    private Thread tickGeneratorThread;
    // 新增slot开始时间戳，用于计算实际耗时
    private long slotStartTimeNs;


    //@PostConstruct
    public void init() {
        try {
            Arrays.fill(nodeId, (byte) 0x01);
            blockProcessor = Executors.newFixedThreadPool(PROCESSOR_THREADS, r -> {
                Thread t = new Thread(r, "poh-block-processor");
                t.setDaemon(true);
                return t;
            });

            log.info("POH引擎初始化完成 - 起始Slot: {}, 起始Tick: {}, 配置: {} tick/slot, {} hashes/tick",
                    GlobalState.getCurrentSlot(), GlobalState.getCurrentTick(),
                    TICKS_PER_SLOT, HASHES_PER_TICK);

            pohCache = Caffeine.newBuilder()
                    .maximumSize(CACHE_SIZE) // 最大缓存条目数
                    .expireAfterAccess(Duration.ofMinutes(10)) // 24小时未访问自动过期（可选，防止内存泄漏）
                    .recordStats() // 记录缓存统计信息（命中率等）
                    .build();

            start();
        } catch (Exception e) {
            log.error("POH引擎初始化失败", e);
            throw new RuntimeException("POH引擎初始化失败", e);
        }
    }


    @Override
    public Result<POHRecord> appendEvent(byte[] eventData) {
        try {
            byte[] eventHash = (eventData != null) ? Sha.applySHA256(eventData) : EMPTY_EVENT_HASH.clone();
            boolean isNonEmpty = eventData != null;

            Object stateLock = GlobalState.getLock();
            synchronized (stateLock) {
                byte[] currentLastHash = GlobalState.getLastHash();
                long currentTick = GlobalState.getCurrentTick();

                long currentSlot = GlobalState.getCurrentSlot();

                byte[] iteratedHash = currentLastHash.clone();
                for (int i = 0; i < HASHES_PER_TICK; i++) {
                    byte[] combinedIter = combine(iteratedHash, longToBytes(i), currentTick);
                    iteratedHash = Sha.applySHA256(combinedIter);
                }
                byte[] combined = combine(iteratedHash, eventHash, currentTick);
                byte[] newHash = Sha.applySHA256(combined);
                POHRecord record = new POHRecord();
                record.setPreviousHash(currentLastHash);
                record.setEventHash(eventHash);
                record.setCurrentHash(newHash.clone());
                record.setSequenceNumber(currentTick);
                pohCache.put(currentTick, record); // 写入缓存

                //递增tick序号
                GlobalState.incrementTick();
                GlobalState.setLastHash(newHash);
                return Result.OK(record);
            }
        } catch (Exception e) {
            log.error("追加POH事件失败", e);
            return Result.error("追加POH事件失败: " + e.getMessage());
        }
    }


    /**
     * 批量追加事件到POH链，整个批量操作期间会占用状态锁，确保事件的连续性
     * @param eventDataList 事件数据列表（单个事件数据可为null，表示空事件）
     * @return 包含批量处理结果的POH记录列表，与输入列表顺序一致
     */
    @Override
    public Result<List<POHRecord>> batchAppendEvent(List<byte[]> eventDataList) {
        if (eventDataList == null || eventDataList.isEmpty()) {
            return Result.OK(new ArrayList<>(0));
        }
        if (eventDataList.size() > BATCH_MAX_SIZE) {
            return Result.error("批量大小超过上限: " + BATCH_MAX_SIZE);
        }

        List<POHRecord> records = new ArrayList<>(eventDataList.size());
        Object stateLock = GlobalState.getLock();

        try {
            synchronized (stateLock) {
                // 批量处理开始时获取当前状态快照
                byte[] currentLastHash = GlobalState.getLastHash();
                long baseTick = GlobalState.getCurrentTick();

                // 逐个处理事件，基于同一基准状态连续计算
                for (byte[] eventData : eventDataList) {
                    byte[] eventHash = (eventData != null) ? Sha.applySHA256(eventData) : EMPTY_EVENT_HASH.clone();

                    // 执行当前事件的哈希迭代计算
                    byte[] iteratedHash = currentLastHash.clone();
                    for (int i = 0; i < HASHES_PER_TICK; i++) {
                        byte[] combinedIter = combine(iteratedHash, longToBytes(i), baseTick);
                        iteratedHash = Sha.applySHA256(combinedIter);
                    }

                    // 计算包含事件的最终哈希
                    byte[] combined = combine(iteratedHash, eventHash, baseTick);
                    byte[] newHash = Sha.applySHA256(combined);

                    // 创建POH记录
                    POHRecord record = new POHRecord();
                    record.setPreviousHash(currentLastHash.clone());
                    record.setEventHash(eventHash);
                    record.setCurrentHash(newHash.clone());
                    record.setSequenceNumber(baseTick);
                    records.add(record);

                    pohCache.put(baseTick, record); // 批量写入缓存

                    // 更新状态用于下一个事件
                    currentLastHash = newHash;
                    baseTick++;
                }

                // 批量处理完成后统一更新全局状态
                GlobalState.setLastHash(currentLastHash);
                // 原子更新tick（批量增加总数量）
                GlobalState.currentTick.addAndGet(eventDataList.size());
            }

            log.debug("批量追加POH事件完成 - 数量: {}, 起始Tick: {}, 结束Tick: {}",
                    records.size(),
                    records.get(0).getSequenceNumber(),
                    records.get(records.size() - 1).getSequenceNumber());
            return Result.OK(records);
        } catch (Exception e) {
            log.error("批量追加POH事件失败", e);
            return Result.error("批量追加事件失败: " + e.getMessage());
        }
    }

    /**
     * 从缓存获取POH记录（用于验证和查询）
     * @param sequenceNumber Tick序号
     * @return 缓存的POHRecord，若未命中返回null
     */
    public POHRecord getFromCache(long sequenceNumber) {
        return pohCache.getIfPresent(sequenceNumber);
    }


    /**
     * 为交易打上POH时间戳，将交易与POH链关联
     * @param transaction 待处理交易
     * @return 包含POH记录的结果对象
     */
    @Override
    public Result<POHRecord> timestampTransaction(Transaction transaction) {
        try {
            // 1. 验证交易合法性
            if (transaction == null) {
                return Result.error("交易对象不能为空");
            }

            // 2. 获取或生成交易唯一标识（确保非空且有效）
            byte[] txId = transaction.getTxId();
            if (txId == null || txId.length == 0) {
                // 若交易未生成ID，主动计算（假设Transaction有生成ID的方法）
                txId = transaction.getTxId();
                if (txId == null || txId.length == 0) {
                    return Result.error("交易ID生成失败，无法生成时间戳");
                }
            }

            // 3. 追加交易事件到POH链（复用appendEvent基础逻辑）
            Result<POHRecord> result = appendEvent(txId);
            if (!result.isSuccess()) {
                return Result.error("追加交易事件到POH链失败: " + result.getMessage());
            }

            // 4. 完善交易相关的POH记录信息
            POHRecord record = result.getData();
            record.setEventHash(txId.clone());


            transaction.setPohRecord(record);
            log.debug("交易[{}]已打上POH时间戳 -  Tick: {}",
                    bytesToHex(txId), record.getSequenceNumber());
            return Result.OK(record);
        } catch (Exception e) {
            log.error("为交易生成时间戳失败", e);
            return Result.error("交易时间戳处理失败: " + e.getMessage());
        }
    }

    /**
     * 计算逻辑时间戳（Slot和Tick的组合表示）
     * @param slot 槽位号
     * @param tick 滴答号
     * @return 逻辑时间戳字符串
     */
    private String computeLogicalTimestamp(long slot, long tick) {
        return String.format("slot=%d,tick=%d", slot, tick);
    }


    /**
     * 生成新的tick
     */
    private void generateTick() {
        Object stateLock = GlobalState.getLock();
        synchronized (stateLock) {
            // 记录当前tick的开始时间
            long tickStartNs = System.nanoTime();

            byte[] currentHash = GlobalState.getLastHash();
            long currentTick = GlobalState.getCurrentTick();

            // 执行哈希计算
            for (int i = 0; i < HASHES_PER_TICK; i++) {
                byte[] combined = combine(currentHash, longToBytes(i), currentTick);
                currentHash = Sha.applySHA256(combined);
            }

            // 创建并缓存Tick记录（空事件）
            POHRecord tickRecord = new POHRecord();
            tickRecord.setPreviousHash(GlobalState.getLastHash());
            tickRecord.setEventHash(EMPTY_EVENT_HASH.clone());
            tickRecord.setCurrentHash(currentHash.clone());
            tickRecord.setSequenceNumber(currentTick);
            pohCache.put(currentTick, tickRecord); // 缓存自动生成的Tick

            // 更新状态
            GlobalState.setLastHash(currentHash);
            long newTick = GlobalState.incrementTick();

            // 计算当前tick的实际耗时
            long tickDurationNs = System.nanoTime() - tickStartNs;

            // 检查是否是新slot的第一个tick
            if (newTick % TICKS_PER_SLOT == 1) {
                slotStartTimeNs = tickStartNs;  // 记录slot开始时间
                log.debug("开始新Slot - 起始Tick: {}, 开始时间: {}", newTick, slotStartTimeNs);
            }

            // 检查是否达到slot边界
            if (newTick % TICKS_PER_SLOT == 0) {
                long newSlot = GlobalState.incrementSlot();
                // 计算整个slot的实际耗时（从第一个tick开始到最后一个tick结束）
                long slotDurationNs = System.nanoTime() - slotStartTimeNs;
                long slotDurationMs = slotDurationNs / 1_000_000;

     /*           log.info("Slot生成完成 - Slot: {}, 最终Tick: {}, 实际耗时: {}ms ({}ns), 哈希: {}",
                        newSlot, newTick, slotDurationMs, slotDurationNs, bytesToHex(currentHash));
*/
            } else {
                // 可选：记录每个tick的耗时，用于性能分析
                log.trace("Tick生成完成 - Tick: {}, 耗时: {}ns", newTick, tickDurationNs);
            }


        }
    }



    @Override
    public Result<List<POHRecord>> batchTimestampTransactions(List<Transaction> transactions) {
        if (transactions == null || transactions.isEmpty()) {
            return Result.OK(new ArrayList<>(0));
        }
        if (transactions.size() > BATCH_MAX_SIZE) {
            return Result.error("批量大小超过上限: " + BATCH_MAX_SIZE);
        }

        List<POHRecord> records = new ArrayList<>(transactions.size());
        for (Transaction tx : transactions) {
            try {
                Result<POHRecord> result = timestampTransaction(tx);
                if (result.isSuccess()) {
                    records.add(result.getData());
                } else {
                    log.warn("交易[{}]时间戳生成失败: {}", bytesToHex(tx.getTxId()), result.getMessage());
                }
            } catch (Exception e) {
                log.warn("处理交易[{}]时发生异常", bytesToHex(tx.getTxId()), e);
            }
        }
        return Result.OK(records);
    }

    @Override
    public byte[] getLastHash() {
        return GlobalState.getLastHash();
    }

    @Override
    public long getCurrentHeight() {
        return GlobalState.getCurrentSlot();
    }

    public long getCurrentTick() {
        return GlobalState.getCurrentTick();
    }

    @Override
    public Result<Boolean> verifyRecords(List<POHRecord> records) {
        if (records == null || records.isEmpty()) {
            return Result.error("待验证的记录列表不能为空");
        }

        // 1. 先验证每条记录自身的哈希合法性性（单条记录的哈希计算正确）
        for (int i = 0; i < records.size(); i++) {
            POHRecord record = records.get(i);
            Result<Boolean> singleVerify = verifyRecord(record);
            if (!singleVerify.isSuccess()) {
                return Result.error("第 " + i + " 条记录验证失败: " + singleVerify.getMessage());
            }
        }

        // 2. 按 sequenceNumber 排序（确保按时间顺序验证）
        List<POHRecord> sortedRecords = new ArrayList<>(records);
        sortedRecords.sort(Comparator.comparingLong(POHRecord::getSequenceNumber));

        // 3. 验证序号严格递增（允许跳跃，因为中间可能有 tick 插入）
        long prevSeq = sortedRecords.get(0).getSequenceNumber();
        for (int i = 1; i < sortedRecords.size(); i++) {
            long currSeq = sortedRecords.get(i).getSequenceNumber();
            if (currSeq <= prevSeq) {
                return Result.error(String.format(
                        "记录 %d 与 %d 序号不递增 - 上一序号: %d, 当前序号: %d",
                        i - 1, i, prevSeq, currSeq
                ));
            }
            prevSeq = currSeq;
        }

        // 4. 验证所有记录属于同一条 POH 链（通过哈希链追溯性验证）
        // 核心逻辑：每条记录的 previousHash 必须能在全局 POH 链中找到对应的前置哈希（可能是前一条记录，也可能是中间的 tick）
        // 这里通过“重放 POH 链”验证：从最早的记录开始，模拟计算到下一条记录的前置哈希，确认能匹配
        POHRecord firstRecord = sortedRecords.get(0);
        byte[] currentChainHash = firstRecord.getPreviousHash().clone(); // 从第一条记录的前置哈希开始
        long currentChainSeq = firstRecord.getSequenceNumber() - 1; // 前置哈希对应的序号

        for (POHRecord record : sortedRecords) {
            long targetSeq = record.getSequenceNumber();
            byte[] targetPrevHash = record.getPreviousHash();

            // 重放从 currentChainSeq 到 targetSeq-1 的哈希计算（模拟中间可能的 tick 或事件）
            // 目标：计算到 targetSeq-1 时的哈希，必须等于 record 的 previousHash
            for (long seq = currentChainSeq + 1; seq < targetSeq; seq++) {
                // 尝试从缓存获取中间Tick记录
                POHRecord cachedRecord = getFromCache(seq);
                if (cachedRecord != null) {
                    // 缓存命中：直接使用缓存的当前哈希，无需重新计算
                    currentChainHash = cachedRecord.getCurrentHash().clone();
                } else {
                    // 缓存未命中：才重新执行哈希计算（兜底逻辑）
                    byte[] iterHash = currentChainHash.clone();
                    for (int i = 0; i < HASHES_PER_TICK; i++) {
                        byte[] combined = combine(iterHash, longToBytes(i), seq);
                        iterHash = Sha.applySHA256(combined);
                    }
                    currentChainHash = iterHash;
                }
                currentChainSeq = seq;
            }

            // 验证重放后的哈希是否与当前记录的 previousHash 一致
            if (!Arrays.equals(currentChainHash, targetPrevHash)) {
                return Result.error(String.format(
                        "记录 %d 与全局POH链断裂 - 预期前置哈希: %s, 实际计算哈希: %s, 序号范围: [%d, %d]",
                        sortedRecords.indexOf(record),
                        bytesToHex(targetPrevHash),
                        bytesToHex(currentChainHash),
                        currentChainSeq + 1,
                        targetSeq
                ));
            }

            // 更新链状态到当前记录的哈希（当前记录已融入链中）
            currentChainHash = record.getCurrentHash().clone();
            currentChainSeq = targetSeq;
        }
        return Result.OK(true);
    }


    /**
     * 验证成功的核心是 复现哈希计算过程，并对比结果是否与记录中的 currentHash 一致，步骤如下：
     * 获取记录中的关键信息：从 POHRecord 中提取 previousHash（上一个哈希）、eventHash（事件哈希）、sequenceNumber（tick 序号）。
     * 复现迭代哈希：使用 previousHash 作为初始值，重复执行 HASHES_PER_TICK 次 SHA-256 计算（与生成时的迭代逻辑完全一致，包括迭代索引和 sequenceNumber 的组合）。
     * 复现最终哈希：将迭代后的哈希与 eventHash 组合，再次计算 SHA-256，得到一个验证用的哈希值。
     * 对比结果：如果验证用的哈希值与记录中的 currentHash 完全一致，则说明该事件的哈希链未被篡改，验证成功。
     * @param record
     * @return
     */
    @Override
    public Result<Boolean> verifyRecord(POHRecord record) {
        if (record == null) {
            return Result.error("待验证的POH记录不能为空");
        }
        try {
            if (record.getPreviousHash() == null || record.getPreviousHash().length != HASH_LENGTH) {
                return Result.error("前序哈希必须为32字节");
            }

            byte[] currentHash = record.getPreviousHash().clone();
            long sequence = record.getSequenceNumber();


            for (int i = 0; i < HASHES_PER_TICK; i++) {
                byte[] combined = combine(currentHash, longToBytes(i), sequence);
                currentHash = Sha.applySHA256(combined);
            }

            byte[] eventCombined = combine(currentHash, record.getEventHash(), sequence);
            byte[] eventHash = Sha.applySHA256(eventCombined);

            if (!Arrays.equals(eventHash, record.getCurrentHash())) {
                return Result.error(String.format(
                        "哈希验证失败 - 预期: %s, 实际: %s",
                        bytesToHex(eventHash),
                        bytesToHex(record.getCurrentHash())
                ));
            }
            return Result.OK(true);
        } catch (Exception e) {
            log.error("单条POH记录验证失败", e);
            return Result.error("验证失败: " + e.getMessage());
        }
    }


    /**
     * 验证记录是否属于同一POH链，若通过则返回按序号排序后的记录
     * @param records 待验证的POH记录列表
     * @return 包含验证结果和排序后记录的封装对象
     */
    public POHVerificationResult verifyAndSortRecords(List<POHRecord> records) {
        // 1. 基础校验：记录不能为空
        if (records == null || records.isEmpty()) {
            return new POHVerificationResult(false, "待验证的记录列表不能为空", null);
        }

        // 2. 验证每条记录自身的哈希合法性
        for (int i = 0; i < records.size(); i++) {
            POHRecord record = records.get(i);
            Result<Boolean> singleVerify = verifyRecord(record);
            if (!singleVerify.isSuccess()) {
                String errorMsg = String.format("第 %d 条记录哈希验证失败: %s", i, singleVerify.getMessage());
                return new POHVerificationResult(false, errorMsg, null);
            }
        }

        // 3. 按序号排序记录（无论后续验证是否通过，先获取排序结果）
        List<POHRecord> sortedRecords = new ArrayList<>(records);
        sortedRecords.sort(Comparator.comparingLong(POHRecord::getSequenceNumber));

        // 4. 验证序号严格递增
        long prevSeq = sortedRecords.getFirst().getSequenceNumber();
        for (int i = 1; i < sortedRecords.size(); i++) {
            long currSeq = sortedRecords.get(i).getSequenceNumber();
            if (currSeq <= prevSeq) {
                String errorMsg = String.format(
                        "记录 %d 与 %d 序号不递增 - 上一序号: %d, 当前序号: %d",
                        i - 1, i, prevSeq, currSeq
                );
                return new POHVerificationResult(false, errorMsg, null);
            }
            prevSeq = currSeq;
        }

        // 5. 验证哈希链连续性（核心：确认所有记录属于同一POH链）
        POHRecord firstRecord = sortedRecords.getFirst();
        byte[] currentChainHash = firstRecord.getPreviousHash().clone();
        long currentChainSeq = firstRecord.getSequenceNumber() - 1;

        for (POHRecord record : sortedRecords) {
            long targetSeq = record.getSequenceNumber();
            byte[] targetPrevHash = record.getPreviousHash();

            // 重放中间的哈希计算，验证链的连续性
            for (long seq = currentChainSeq + 1; seq < targetSeq; seq++) {
                // 尝试从缓存获取中间Tick记录
                POHRecord cachedRecord = getFromCache(seq);
                if (cachedRecord != null) {
                    // 缓存命中：直接使用缓存的当前哈希，无需重新计算
                    currentChainHash = cachedRecord.getCurrentHash().clone();
                } else {
                    // 缓存未命中：才重新执行哈希计算（兜底逻辑）
                    byte[] iterHash = currentChainHash.clone();
                    for (int i = 0; i < HASHES_PER_TICK; i++) {
                        byte[] combined = combine(iterHash, longToBytes(i), seq);
                        iterHash = Sha.applySHA256(combined);
                    }
                    currentChainHash = iterHash;
                }
                currentChainSeq = seq;
            }

            // 验证当前记录的前置哈希是否与链计算结果一致
            if (!Arrays.equals(currentChainHash, targetPrevHash)) {
                String errorMsg = String.format(
                        "记录 %d 与全局POH链断裂 - 预期前置哈希: %s, 实际计算哈希: %s",
                        sortedRecords.indexOf(record),
                        bytesToHex(targetPrevHash),
                        bytesToHex(currentChainHash)
                );
                return new POHVerificationResult(false, errorMsg, null);
            }

            // 更新链状态
            currentChainHash = record.getCurrentHash().clone();
            currentChainSeq = targetSeq;
        }

        // 所有验证通过，返回排序后的记录
        return new POHVerificationResult(true, "验证通过，记录已按序号排序", sortedRecords);
    }



    @Override
    public void start() {
        if (isRunning) {
            log.warn("POH引擎已处于运行状态");
            return;
        }

        isRunning = true;
        tickGeneratorThread = new Thread(this::tickGenerationLoop, "poh-tick-generator");
        tickGeneratorThread.setDaemon(true);
        tickGeneratorThread.start();
        log.info("POH引擎启动成功 - 开始生成tick");
    }

    @Override
    public void stop() {
        if (!isRunning) {
            log.warn("POH引擎已处于停止状态");
            return;
        }

        isRunning = false;

        if (tickGeneratorThread != null) {
            tickGeneratorThread.interrupt();
            try {
                tickGeneratorThread.join(1000);
            } catch (InterruptedException e) {
                log.warn("tick生成线程停止超时", e);
            }
        }

        if (blockProcessor != null) {
            blockProcessor.shutdown();
        }

        log.info("POH引擎已停止 - 最终Slot: {}, 最终Tick: {}",
                GlobalState.getCurrentSlot(), GlobalState.getCurrentTick());
    }

    /**
     * 无休眠的Tick生成循环
     * 持续计算tick，通过时间戳准确记录每个slot的实际耗时
     */
    private void tickGenerationLoop() {
        log.info("Tick生成线程启动 - 配置: {} hashes/tick, {} ticks/slot (无休眠模式)",
                HASHES_PER_TICK, TICKS_PER_SLOT);
        // 初始化第一个slot的开始时间
        if (GlobalState.getCurrentTick() % TICKS_PER_SLOT == 0) {
            slotStartTimeNs = System.nanoTime();
        }
        while (isRunning) {
            try {
                // 持续生成tick，不进行主动休眠
                generateTick();

                // 检查中断标志（允许优雅停止）
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException();
                }
            } catch (InterruptedException e) {
                log.info("Tick生成线程被中断");
                break;
            } catch (Exception e) {
                log.error("Tick生成失败", e);
                // 发生异常时短暂停顿，避免CPU空转
                try {
                    Thread.sleep(10);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        log.info("Tick生成线程停止");
    }
}