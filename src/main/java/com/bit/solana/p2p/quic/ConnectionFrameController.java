package com.bit.solana.p2p.quic;

import java.util.concurrent.TimeUnit;

public class ConnectionFrameController {
    private final long connectionId; // 连接唯一标识
    private int inFlightFrames; // 当前在途帧数（已发送未ACK）
    private final int maxInFlightFrames = 8192; // 最大在途帧上限

    // 速率控制参数
    private int currentSendRate; // 当前发送速率（帧/秒）
    private final int minSendRate = 512; // 初始/最小发送速率
    private final int maxSendRate = 8192; // 最大发送速率
    private long currentSecondTimestamp; // 当前秒时间戳（用于速率计数）
    private int framesSentInCurrentSecond; // 当前秒内已发送帧数

    // ACK反馈调整参数
    private int consecutiveAckCount; // 连续ACK计数（用于触发速率提升）
    private static final int ACK_TRIGGER_THRESHOLD = 200; // 每收到200个连续ACK触发提速
    private static final float RATE_INCREMENT_FACTOR = 1.2f; // 速率提升因子（每次提升20%）

    public ConnectionFrameController(long connectionId) {
        this.connectionId = connectionId;
        this.inFlightFrames = 0;
        this.currentSendRate = minSendRate;
        this.currentSecondTimestamp = System.currentTimeMillis() / 1000;
        this.framesSentInCurrentSecond = 0;
        this.consecutiveAckCount = 0;
    }

    /**
     * 检查是否允许发送新帧（受限于在途帧上限和速率）
     */
    public synchronized boolean canSendFrame() {
        long currentSecond = System.currentTimeMillis() / 1000;
        // 跨秒重置计数
        if (currentSecond != currentSecondTimestamp) {
            currentSecondTimestamp = currentSecond;
            framesSentInCurrentSecond = 0;
        }
        // 检查在途帧是否超限，且当前秒发送量未达速率上限
        return inFlightFrames < maxInFlightFrames
                && framesSentInCurrentSecond < currentSendRate;
    }

    /**
     * 发送帧后更新状态
     */
    public synchronized void onFrameSent() {
        inFlightFrames++;
        framesSentInCurrentSecond++;
    }

    /**
     * 收到ACK后更新状态（减少在途帧，尝试提升速率）
     */
    public synchronized void onAckReceived() {
        if (inFlightFrames > 0) {
            inFlightFrames--;
        }
        // 累计连续ACK，达到阈值且未达最大速率则提升
        consecutiveAckCount++;
        if (consecutiveAckCount >= ACK_TRIGGER_THRESHOLD && currentSendRate < maxSendRate) {
            int newRate = (int) (currentSendRate * RATE_INCREMENT_FACTOR);
            currentSendRate = Math.min(newRate, maxSendRate); // 不超过最大速率
            consecutiveAckCount = 0; // 重置计数
        }
    }

    /**
     * 检测到丢包时降级速率（可选，用于稳定性保障）
     */
    public synchronized void onFrameLost() {
        consecutiveAckCount = 0; // 重置连续ACK计数
        currentSendRate = Math.max((int)(currentSendRate * 0.8), minSendRate); // 降低20%，不低于最小值
    }

    // Getter方法（用于监控和调试）
    public int getInFlightFrames() { return inFlightFrames; }
    public int getCurrentSendRate() { return currentSendRate; }
}