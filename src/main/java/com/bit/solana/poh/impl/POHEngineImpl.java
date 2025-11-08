package com.bit.solana.poh.impl;

import com.bit.solana.poh.POHCache;
import com.bit.solana.poh.POHEngine;
import com.bit.solana.poh.POHException;
import com.bit.solana.poh.POHRecord;
import com.bit.solana.result.Result;
import com.bit.solana.structure.tx.Transaction;
import com.bit.solana.util.Sha;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import static com.bit.solana.util.ByteUtils.longToBytes;


@Slf4j
@Component
public class POHEngineImpl implements POHEngine {
    @Autowired
    private POHCache pohCache;
    // 当前哈希值
    private final AtomicLong sequenceNumber = new AtomicLong(0);

    // 最新哈希值
    private final byte[] lastHash = new byte[32];

    // 节点ID（实际环境中应配置）
    private final byte[] nodeId = new byte[32];

    // 批量处理线程池
    private ExecutorService batchProcessor;

    // 空事件间隔（纳秒）
    private static final long EMPTY_EVENT_INTERVAL_NS = 1_000_000; // 1ms

    // 最大批量处理大小
    private static final int BATCH_MAX_SIZE = 1000;

    // 运行状态标志
    private volatile boolean isRunning = false;

    // 空事件生成线程
    private Thread emptyEventThread;

    @PostConstruct
    public void init() {
        try {
            // 初始化缓存
            pohCache.initDefaultState();

            // 从缓存加载最后状态
            byte[] lastHashFromCache = pohCache.getBytes(POHCache.KEY_LAST_HASH);
            if (lastHashFromCache != null) {
                System.arraycopy(lastHashFromCache, 0, lastHash, 0, lastHash.length);
            }

            long chainHeight = pohCache.getLong(POHCache.KEY_CHAIN_HEIGHT);
            sequenceNumber.set(chainHeight);

            // 初始化线程池
            batchProcessor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 2);

            log.info("POH引擎初始化完成，起始序列号: {}", sequenceNumber.get());
        } catch (POHException e) {
            log.error("POH引擎初始化失败", e);
            throw new RuntimeException("POH引擎初始化失败", e);
        }
    }

    @Override
    public synchronized Result<POHRecord> appendEvent(byte[] eventData) {
        try {
            // 计算事件哈希
            byte[] eventHash = eventData != null ? Sha.applySHA256(eventData) : new byte[32];

            // 计算新哈希 = SHA256(lastHash + eventHash + sequenceNumber)
            byte[] combined = combine(lastHash, eventHash, sequenceNumber.get());
            byte[] newHash = Sha.applySHA256(combined);

            // 创建记录
            POHRecord record = new POHRecord();
            record.setEventHash(eventHash);
            record.setPreviousHash(lastHash.clone());
            record.setSequenceNumber(sequenceNumber.get());
            record.setPhysicalTimestamp(Instant.now());
            record.setNodeId(nodeId.clone());

            // 更新状态
            System.arraycopy(newHash, 0, lastHash, 0, lastHash.length);
            sequenceNumber.incrementAndGet();

            // 定期同步到缓存
            if (sequenceNumber.get() % 1000 == 0) {
                pohCache.putBytes(POHCache.KEY_LAST_HASH, lastHash);
                pohCache.putLong(POHCache.KEY_CHAIN_HEIGHT, sequenceNumber.get());
            }

            return Result.OK(record);
        } catch (Exception e) {
            log.error("追加POH事件失败", e);
            return Result.error("追加POH事件失败: " + e.getMessage());
        }
    }


    @Override
    public Result<POHRecord> timestampTransaction(Transaction transaction) {
        try {
            // 获取交易ID作为事件数据
            byte[] txId = transaction.getTxId();

            // 追加事件并获取记录
            Result<POHRecord> result = appendEvent(txId);
            if (result.isSuccess()) {
                // 设置交易ID到记录中
                POHRecord record = result.getData();
                record.setTransactionId(txId);
                return Result.OK(record);
            }

            return result;
        } catch (Exception e) {
            log.error("为交易生成POH时间戳失败", e);
            return Result.error("为交易生成POH时间戳失败: " + e.getMessage());
        }
    }

    @Override
    public Result<List<POHRecord>> batchTimestampTransactions(List<Transaction> transactions) {
        try {
            if (transactions.size() > BATCH_MAX_SIZE) {
                return Result.error("批量处理大小超过上限: " + BATCH_MAX_SIZE);
            }

            List<POHRecord> records = new ArrayList<>(transactions.size());

            // 同步处理以保证顺序性
            for (Transaction tx : transactions) {
                Result<POHRecord> result = timestampTransaction(tx);
                if (result.isSuccess()) {
                    records.add(result.getData());
                } else {
                    log.warn("交易{}的POH时间戳生成失败: {}", tx.getTxId(), result.getMessage());
                }
            }

            return Result.OK(records);
        } catch (Exception e) {
            log.error("批量为交易生成POH时间戳失败", e);
            return Result.error("批量为交易生成POH时间戳失败: " + e.getMessage());
        }
    }

    @Override
    public byte[] getLastHash() {
        synchronized (lastHash) {
            return lastHash.clone();
        }
    }

    @Override
    public Result<Boolean> verifyRecords(List<POHRecord> records) {
        try {
            if (records == null || records.isEmpty()) {
                return Result.OK(true);
            }

            // 验证记录链的连续性和哈希正确性
            POHRecord previous = records.get(0);
            for (int i = 1; i < records.size(); i++) {
                POHRecord current = records.get(i);

                // 验证序列号连续
                if (current.getSequenceNumber() != previous.getSequenceNumber() + 1) {
                    return Result.error("POH记录序列号不连续，位置: " + i);
                }

                // 验证哈希链
                byte[] combined = combine(
                        previous.getPreviousHash(),
                        previous.getEventHash(),
                        previous.getSequenceNumber()
                );
                byte[] expectedHash = Sha.applySHA256(combined);

                if (!java.util.Arrays.equals(current.getPreviousHash(), expectedHash)) {
                    return Result.error("POH记录哈希验证失败，位置: " + i);
                }

                previous = current;
            }

            return Result.OK(true);
        } catch (Exception e) {
            log.error("验证POH记录失败", e);
            return Result.error("验证POH记录失败: " + e.getMessage());
        }
    }


    /**
     * 组合数据用于哈希计算
     */
    private byte[] combine(byte[] lastHash, byte[] eventHash, long sequence) {
        byte[] sequenceBytes = longToBytes(sequence);
        byte[] combined = new byte[lastHash.length + eventHash.length + sequenceBytes.length];

        System.arraycopy(lastHash, 0, combined, 0, lastHash.length);
        System.arraycopy(eventHash, 0, combined, lastHash.length, eventHash.length);
        System.arraycopy(sequenceBytes, 0, combined, lastHash.length + eventHash.length, sequenceBytes.length);

        return combined;
    }

    @Override
    public void start() {
        isRunning = true;

        // 启动空事件生成线程
        emptyEventThread = new Thread(this::generateEmptyEvents, "poh-empty-event-generator");
        emptyEventThread.setDaemon(true);
        emptyEventThread.start();

        log.info("POH引擎已启动");
    }

    @Override
    public void stop() {
        isRunning = false;

        if (emptyEventThread != null) {
            emptyEventThread.interrupt();
            try {
                emptyEventThread.join(1000);
            } catch (InterruptedException e) {
                log.warn("POH空事件线程停止超时", e);
            }
        }

        if (batchProcessor != null) {
            batchProcessor.shutdown();
        }

        // 保存最后状态到缓存
        try {
            pohCache.putBytes(POHCache.KEY_LAST_HASH, lastHash);
            pohCache.putLong(POHCache.KEY_CHAIN_HEIGHT, sequenceNumber.get());
        } catch (POHException e) {
            log.error("POH引擎停止时保存状态失败", e);
        }

        log.info("POH引擎已停止");
    }

    /**
     * 生成空事件以保持POH链的连续性
     */
    private void generateEmptyEvents() {
        log.info("POH空事件生成线程已启动");

        while (isRunning) {
            try {
                // 定期生成空事件
                Thread.sleep(0, (int) EMPTY_EVENT_INTERVAL_NS);
                appendEvent(null);
            } catch (InterruptedException e) {
                log.info("POH空事件生成线程被中断");
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("生成POH空事件失败", e);
            }
        }
    }
}
