package com.bit.solana.p2p.quic.control;

import lombok.Data;

/**
 * 拥塞控制状态信息
 */
@Data
public class CongestionState {
    
    // 拥塞窗口相关
    private long congestionWindow;        // 当前拥塞窗口（字节）
    private long slowStartThreshold;      // 慢启动阈值（字节）
    
    // 发送速率相关
    private long currentSendRate;        // 当前发送速率（字节/秒）
    private long maxSendRate;           // 最大发送速率（字节/秒）
    
    // RTT相关
    private long smoothedRtt;          // 平滑RTT（毫秒）
    private long rttVariance;           // RTT方差（毫秒）
    private long minRtt;               // 最小RTT（毫秒）
    
    // 状态统计
    private boolean inSlowStart;         // 是否在慢启动阶段
    private boolean inCongestionAvoidance; // 是否在拥塞避免阶段
    private boolean inRecovery;         // 是否在恢复阶段
    
    // 性能统计
    private long totalAckedBytes;       // 总确认字节数
    private long totalLostBytes;        // 总丢失字节数
    private long packetsSent;           // 发送包数
    private long packetsLost;           // 丢失包数
    
    // 时间信息
    private long lastUpdateTime;        // 上次更新时间
    private long connectionStartTime;    // 连接开始时间
    
    public CongestionState() {
        this.congestionWindow = 10 * 1024;     // 初始10KB
        this.slowStartThreshold = Long.MAX_VALUE;  // 初始无限制
        this.currentSendRate = 1024 * 1024;      // 初始1MB/s
        this.maxSendRate = 10 * 1024 * 1024;     // 最大10MB/s
        this.minRtt = Long.MAX_VALUE;
        this.inSlowStart = true;
        this.lastUpdateTime = System.currentTimeMillis();
        this.connectionStartTime = System.currentTimeMillis();
    }
    
    /**
     * 获取丢包率
     */
    public double getPacketLossRate() {
        if (packetsSent == 0) return 0.0;
        return (double) packetsLost / packetsSent;
    }
    
    /**
     * 获取平均吞吐量（字节/秒）
     */
    public double getAverageThroughput() {
        long duration = System.currentTimeMillis() - connectionStartTime;
        if (duration == 0) return 0.0;
        return (double) totalAckedBytes * 1000 / duration;
    }
    
    /**
     * 更新RTT统计
     */
    public void updateRtt(long rtt) {
        if (minRtt == Long.MAX_VALUE || rtt < minRtt) {
            minRtt = rtt;
        }
        
        if (smoothedRtt == 0) {
            smoothedRtt = rtt;
            rttVariance = rtt / 2;
        } else {
            // Jacobson/Karels算法
            rttVariance = (3 * rttVariance + Math.abs(smoothedRtt - rtt)) / 4;
            smoothedRtt = (7 * smoothedRtt + rtt) / 8;
        }
    }
    
    /**
     * 重置统计信息
     */
    public void resetStats() {
        totalAckedBytes = 0;
        totalLostBytes = 0;
        packetsSent = 0;
        packetsLost = 0;
        lastUpdateTime = System.currentTimeMillis();
        connectionStartTime = System.currentTimeMillis();
    }
}