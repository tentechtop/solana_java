package com.bit.solana.p2p.quic;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 端到端的帧流量控制
 */
public class FrameFlowController {
    private final long connectionId; // 连接唯一标识

    private final AtomicInteger inFlightFrames = new AtomicInteger(0);
    private final AtomicInteger framesSentInCurrentSecond = new AtomicInteger(0);
    private final int maxInFlightFrames = 8192; // 最大在途帧上限
    private int currentSendRate; // 当前发送速率（帧/秒）
    private final int minSendRate = 512; // 初始/最小发送速率
    private final int maxSendRate = 8192; // 最大发送速率
    private long currentSecondTimestamp; // 当前秒时间戳（用于速率计数）
    private int consecutiveAckCount; // 连续ACK计数（用于触发速率提升）
    private static final int ACK_TRIGGER_THRESHOLD = 200; // 每收到200个连续ACK触发提速
    private static final float RATE_INCREMENT_FACTOR = 1.2f; // 速率提升因子（每次提升20%）


    public FrameFlowController(long connectionId) {
        this.connectionId = connectionId;
        this.currentSendRate = minSendRate;
        this.currentSecondTimestamp = System.currentTimeMillis() / 1000;
        this.consecutiveAckCount = 0;
    }

    /**
     * 能否发送
     */

    /**
     * 批量ACK
     */
    /**
     * ACK
     */

    /**
     * 批量发送
     */
    /**
     * 发送
     */

    /**
     * 调节发送速度
     */

}