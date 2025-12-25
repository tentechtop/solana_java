package com.bit.solana.p2p.quic;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 端到端的帧流量控制（单连接粒度）
 * 控制单连接的在途帧数量、发送速率（帧/秒）
 */
public class ConnectFrameFlowController {
    private final long connectionId; // 连接唯一标识

    private final AtomicInteger inFlightFrames = new AtomicInteger(0); // 当前在途帧数量（已发送未ACK）
    private final AtomicInteger framesSentInCurrentSecond = new AtomicInteger(0); // 当前秒内已发送帧数量
    private final int maxInFlightFrames = 8192; // 单连接最大在途帧上限
    private volatile int currentSendRate; // 当前发送速率（帧/秒），volatile保证多线程可见性
    private final int minSendRate = 512; // 初始/最小发送速率
    private final int maxSendRate = 8192; // 单连接最大发送速率
    private volatile long currentSecondTimestamp; // 当前秒时间戳（用于速率计数）
    private final AtomicInteger consecutiveAckCount = new AtomicInteger(0); // 连续ACK计数（用于触发速率提升）
    private static final int ACK_TRIGGER_THRESHOLD = 200; // 每收到200个连续ACK触发提速
    private static final float RATE_INCREMENT_FACTOR = 1.2f; // 速率提升因子（每次提升20%）
    private static final float RATE_DECREMENT_FACTOR = 0.8f; // 速率下降因子（出现异常时下降20%）

    //帧平均时间 纳秒
    private static final long[] frameAverageSendTimes = new long[1024];



    public ConnectFrameFlowController(long connectionId) {
        this.connectionId = connectionId;
        this.currentSendRate = minSendRate;
        this.currentSecondTimestamp = System.currentTimeMillis() / 1000; // 初始化当前秒时间戳
        this.consecutiveAckCount.set(0);
    }

    /**
     * 判断当前连接是否可以发送单个帧
     * 需同时满足：在途帧未超限、当前秒发送速率未超限
     * @return true-可以发送，false-不可发送
     */
    public boolean canSendSingleFrame() {
        // 1. 检查在途帧是否超限
        if (inFlightFrames.get() >= maxInFlightFrames) {
            return false;
        }
        // 2. 检查当前秒发送速率是否超限（先更新时间戳，处理跨秒场景）
        updateSecondTimestamp();
        return framesSentInCurrentSecond.get() < currentSendRate;
    }

    /**
     * 判断当前连接是否可以批量发送帧
     * @param batchSize 待批量发送的帧数量
     * @return true-可以批量发送，false-不可批量发送
     */
    public boolean canSendBatchFrames(int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("批量发送的帧数量不能小于等于0");
        }
        // 1. 检查在途帧是否会超限
        if (inFlightFrames.get() + batchSize > maxInFlightFrames) {
            return false;
        }
        // 2. 检查当前秒发送速率是否会超限
        updateSecondTimestamp();
        return framesSentInCurrentSecond.get() + batchSize <= currentSendRate;
    }

    /**
     * 单个帧发送成功后，更新统计信息
     */
    public void onFrameSent() {
        // 1. 递增在途帧数量
        inFlightFrames.incrementAndGet();
        // 2. 递增当前秒发送帧数量（先更新时间戳）
        updateSecondTimestamp();
        framesSentInCurrentSecond.incrementAndGet();
    }

    /**
     * 批量帧发送成功后，更新统计信息
     * @param batchSize 已发送的批量帧数量
     */
    public void onBatchFramesSent(int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("已发送的批量帧数量不能小于等于0");
        }
        // 1. 递增在途帧数量
        inFlightFrames.addAndGet(batchSize);
        // 2. 递增当前秒发送帧数量（先更新时间戳）
        updateSecondTimestamp();
        framesSentInCurrentSecond.addAndGet(batchSize);
    }

    /**
     * 单个帧收到ACK后，更新统计信息
     */
    public void onFrameAcked() {
        // 1. 递减在途帧数量（保证不小于0）
        inFlightFrames.updateAndGet(count -> Math.max(count - 1, 0));
        // 2. 递增连续ACK计数，并判断是否触发速率提升
        int currentAckCount = consecutiveAckCount.incrementAndGet();
        if (currentAckCount >= ACK_TRIGGER_THRESHOLD) {
            adjustSendRate(true); // 触发速率提升
            consecutiveAckCount.set(0); // 重置连续ACK计数
        }
    }

    /**
     * 批量帧收到ACK后，更新统计信息
     * @param batchSize 已ACK的批量帧数量
     */
    public void onBatchFramesAcked(int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("已ACK的批量帧数量不能小于等于0");
        }
        // 1. 递减在途帧数量（保证不小于0）
        inFlightFrames.updateAndGet(count -> Math.max(count - batchSize, 0));
        // 2. 递增连续ACK计数，并判断是否触发速率提升
        int currentAckCount = consecutiveAckCount.addAndGet(batchSize);
        if (currentAckCount >= ACK_TRIGGER_THRESHOLD) {
            adjustSendRate(true); // 触发速率提升
            consecutiveAckCount.set(currentAckCount - ACK_TRIGGER_THRESHOLD); // 保留超出阈值的部分，避免重复计数
        }
    }

    /**
     * 帧发送失败/超时（未收到ACK），触发速率下降
     */
    public void onFrameSendFailed() {
        consecutiveAckCount.set(0); // 重置连续ACK计数
        adjustSendRate(false); // 触发速率下降
    }

    /**
     * 调节发送速度
     * @param isIncrement true-速率提升（基于ACK），false-速率下降（基于发送失败/超时）
     */
    private void adjustSendRate(boolean isIncrement) {
        int newRate;
        if (isIncrement) {
            // 速率提升：当前速率 * 提升因子，不超过最大速率
            newRate = (int) Math.min(currentSendRate * RATE_INCREMENT_FACTOR, maxSendRate);
        } else {
            // 速率下降：当前速率 * 下降因子，不低于最小速率
            newRate = (int) Math.max(currentSendRate * RATE_DECREMENT_FACTOR, minSendRate);
        }
        // 只有速率发生变化时才更新（避免无效赋值）
        if (newRate != currentSendRate) {
            currentSendRate = newRate;
//            System.out.printf("连接[%d]发送速率调节：%d -> %d（帧/秒）%n", connectionId, (int)(isIncrement ? newRate/RATE_INCREMENT_FACTOR : newRate/RATE_DECREMENT_FACTOR), newRate);
        }
    }

    /**
     * 更新当前秒时间戳（处理跨秒场景，重置当前秒发送计数）
     */
    private void updateSecondTimestamp() {
        long currentTime = System.currentTimeMillis() / 1000;
        // 如果当前时间已跨秒，重置当前秒发送计数和时间戳
        if (currentTime != currentSecondTimestamp) {
            currentSecondTimestamp = currentTime;
            framesSentInCurrentSecond.set(0);
        }
    }

    // ------------------- Getter 方法（用于全局流量控制统计） -------------------
    public long getConnectionId() {
        return connectionId;
    }

    public int getInFlightFrames() {
        return inFlightFrames.get();
    }

    public int getCurrentSendRate() {
        return currentSendRate;
    }

    public int getMaxInFlightFrames() {
        return maxInFlightFrames;
    }
}