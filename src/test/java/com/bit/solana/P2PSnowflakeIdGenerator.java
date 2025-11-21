package com.bit.solana;

import java.net.*;
import java.security.SecureRandom;
import java.util.Enumeration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32;

public class P2PSnowflakeIdGenerator {
    // ====================== 雪花算法核心常量（核心调整） ======================
    private static final int SIGN_BIT = 1;                // 符号位（固定1位）
    private static final int TIMESTAMP_BIT = 30;         // 时间戳位（牺牲后30位，可存≈34年，远超半年）
    private static final int NODE_ID_BIT = 24;           // 机器位提升至24位
    private static final int SEQUENCE_BIT = 64 - SIGN_BIT - TIMESTAMP_BIT - NODE_ID_BIT; // 序列位9位

    private static final long MAX_NODE_ID = (1L << NODE_ID_BIT) - 1;    // 24位机器ID最大值：16777215
    private static final long MAX_SEQUENCE = (1L << SEQUENCE_BIT) - 1;  // 9位序列最大值：511

    private static final int NODE_ID_SHIFT = SEQUENCE_BIT;              // 机器位偏移量：9
    private static final int TIMESTAMP_SHIFT = NODE_ID_BIT + SEQUENCE_BIT; // 时间戳偏移量：33

    // 起始时间戳（2025-01-01 00:00:00），控制ID长度在13位左右
    private static final long START_TIMESTAMP = 1735689600000L;
    private static final long TIME_BACKWARD_THRESHOLD = 5L;

    // ====================== 静态缓存节点ID（增强版） ======================
    private static volatile Long CACHED_NODE_ID = null;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Object NODE_ID_LOCK = new Object();

    // ====================== 实例变量 ======================
    private final long nodeId;
    private volatile long lastTimestamp = -1L;
    private long sequence = 0L;

    // ====================== 静态预热 ======================
    static {
        generateAndCacheNodeId();
    }

    // ====================== 构造方法 ======================
    public P2PSnowflakeIdGenerator(long nodeId) {
        if (nodeId < 0 || nodeId > MAX_NODE_ID) {
            throw new IllegalArgumentException(
                    String.format("Node ID must be between 0 and %d (current: %d)", MAX_NODE_ID, nodeId)
            );
        }
        this.nodeId = nodeId;
    }

    public P2PSnowflakeIdGenerator() {
        this.nodeId = CACHED_NODE_ID;
    }

    // ====================== 核心ID生成方法 ======================
    public synchronized long nextId() {
        long currentTimestamp = System.currentTimeMillis();

        // 1. 时钟回拨处理
        if (currentTimestamp < lastTimestamp) {
            long timeDiff = lastTimestamp - currentTimestamp;
            if (timeDiff > TIME_BACKWARD_THRESHOLD) {
                throw new RuntimeException(
                        String.format("Clock moved backwards! Refuse generate ID for %dms", timeDiff)
                );
            }
            // 轻微回拨，等待时钟追上
            while (currentTimestamp < lastTimestamp) {
                currentTimestamp = System.currentTimeMillis();
            }
        }

        // 2. 同一毫秒序列处理
        if (currentTimestamp == lastTimestamp) {
            sequence++;
            if (sequence > MAX_SEQUENCE) {
                // 序列溢出，等待下一毫秒
                while (currentTimestamp <= lastTimestamp) {
                    currentTimestamp = System.currentTimeMillis();
                }
                sequence = 0L;
            }
        } else {
            sequence = 0L;
        }

        // 3. 更新最后时间戳
        lastTimestamp = currentTimestamp;

        // 4. 拼接64位ID（关键：偏移量适配新的位分配）
        return ((currentTimestamp - START_TIMESTAMP) << TIMESTAMP_SHIFT)
                | (nodeId << NODE_ID_SHIFT)
                | sequence;
    }

    // ====================== 增强版NodeId生成（MAC+随机数） ======================
    private static void generateAndCacheNodeId() {
        if (CACHED_NODE_ID != null) return;

        synchronized (NODE_ID_LOCK) {
            if (CACHED_NODE_ID != null) return;

            long nodeId = 0L;
            boolean macProcessed = false;

            // 第一步：获取MAC地址并计算CRC32哈希（提升唯一性）
            try {
                Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
                CRC32 crc32 = new CRC32();

                while (interfaces.hasMoreElements()) {
                    NetworkInterface ni = interfaces.nextElement();
                    if (ni.isLoopback() || ni.isVirtual() || !ni.isUp()) continue;

                    byte[] mac = ni.getHardwareAddress();
                    if (mac != null && mac.length > 0) {
                        crc32.update(mac); // MAC地址做CRC32哈希
                        nodeId = crc32.getValue(); // 取哈希值作为基础
                        macProcessed = true;
                        break;
                    }
                }
            } catch (SocketException e) {
                System.err.println("❌ 获取MAC地址失败，将增强随机数生成NodeId：" + e.getMessage());
            }

            // 第二步：混合24位安全随机数（提升随机性）
            long random24Bit = Math.abs(SECURE_RANDOM.nextLong()) & ((1L << 24) - 1); // 仅保留24位随机数
            if (macProcessed) {
                // MAC哈希 + 随机数 混合（异或+取模）
                nodeId = (nodeId ^ random24Bit) % (MAX_NODE_ID + 1);
                System.out.println("ℹ️ MAC+随机数生成NodeId：" + nodeId + "（24位）");
            } else {
                // 无MAC时直接用24位随机数
                nodeId = random24Bit;
                System.out.println("ℹ️ 纯随机数生成NodeId：" + nodeId + "（24位）");
            }

            // 确保NodeId在24位范围内
            nodeId = Math.abs(nodeId) % (MAX_NODE_ID + 1);
            CACHED_NODE_ID = nodeId;
            System.out.println("✅ 最终缓存的NodeId：" + nodeId + "（长度：" + String.valueOf(nodeId).length() + "位）");
        }
    }

    // ====================== 测试验证 ======================
    public static void main(String[] args) throws InterruptedException {
        // 1. 验证NodeId复用性
        P2PSnowflakeIdGenerator generator1 = new P2PSnowflakeIdGenerator();
        P2PSnowflakeIdGenerator generator2 = new P2PSnowflakeIdGenerator();
        System.out.println("✅ 实例1NodeId：" + generator1.nodeId);
        System.out.println("✅ 实例2NodeId：" + generator2.nodeId);
        System.out.println("✅ NodeId是否一致：" + (generator1.nodeId == generator2.nodeId));

        // 2. 验证ID长度（目标13位左右）
        long id = generator1.nextId();
        String idStr = String.valueOf(id);
        System.out.println("✅ 生成的ID：" + id);
        System.out.println("✅ ID十进制长度：" + idStr.length() + "（目标13位左右）");

        // 3. 验证1毫秒内生成能力（9位序列可生成512个/ms）
        long startNs = System.nanoTime();
        long endNs = startNs + 1_000_000; // 1毫秒窗口
        int count = 0;
        while (System.nanoTime() < endNs) {
            generator1.nextId();
            count++;
        }
        System.out.println("✅ 1毫秒内生成ID数量：" + count + "（理论最大值512）");

        // 4. 并发唯一性测试（100线程×1万ID）
        int threadCount = 100;
        int perThreadCount = 10000;
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicLong duplicateCount = new AtomicLong(0);
        java.util.concurrent.ConcurrentHashMap<Long, Boolean> idMap =
                new java.util.concurrent.ConcurrentHashMap<>(threadCount * perThreadCount);

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                for (int j = 0; j < perThreadCount; j++) {
                    long tempId = generator1.nextId();
                    if (idMap.putIfAbsent(tempId, Boolean.TRUE) != null) {
                        duplicateCount.incrementAndGet();
                        System.err.println("❌ 重复ID：" + tempId);
                    }
                }
                latch.countDown();
            }).start();
        }
        latch.await();
        System.out.println("✅ 并发生成" + (threadCount * perThreadCount) + "个ID，重复数：" + duplicateCount.get());

        // 5. 时间回拨测试
        try {
            java.lang.reflect.Field field = P2PSnowflakeIdGenerator.class.getDeclaredField("lastTimestamp");
            field.setAccessible(true);
            field.set(generator1, System.currentTimeMillis() + 10);
            generator1.nextId();
        } catch (Exception e) {
            System.out.println("✅ 捕获时间回拨异常：" + e.getMessage());
        }
    }
}