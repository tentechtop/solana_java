package com.bit.solana.p2p.quic.control;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * QUIC拥塞控制器
 * 实现基于BBR和CUBIC混合的拥塞控制算法
 */
@Slf4j
@Data
public class QuicCongestionControl {
    // 连接ID
    private final long connectionId;

    // 拥塞窗口相关
    private final AtomicLong congestionWindow = new AtomicLong(10 * 1024); // 初始拥塞窗口 10KB
    private final AtomicLong slowStartThreshold = new AtomicLong(Long.MAX_VALUE); // 慢启动阈值
    private final long minCongestionWindow = 2 * 1024; // 最小拥塞窗口 2KB
    private final long maxCongestionWindow = 10 * 1024 * 1024; // 最大拥塞窗口 10MB

    // RTT测量
    private volatile long minRtt = Long.MAX_VALUE; // 最小RTT
    private volatile long maxRtt = 0; // 最大RTT
    private volatile long baseRtt = Long.MAX_VALUE; // 基础RTT (当前最小RTT)
    private final AtomicLong avgRtt = new AtomicLong(100); // 平均RTT，初始100ms
    private volatile long rttVariance = 0; // RTT方差
    private final AtomicInteger rttSampleCount = new AtomicInteger(0); // RTT样本计数

    // 发送速率控制
    private final AtomicLong sendingRate = new AtomicLong(0); // 当前发送速率 (bytes/sec)
    private final AtomicLong deliveryRate = new AtomicLong(0); // 交付速率 (bytes/sec)
    private long lastDeliveryTime = System.currentTimeMillis(); // 上次交付时间
    private long deliveredBytes = 0; // 已交付字节数

    // 状态控制
    private volatile boolean inSlowStart = true; // 是否处于慢启动阶段
    private volatile long lastLossEventTime = 0; // 上次丢包时间
    private final AtomicInteger lossCount = new AtomicInteger(0); // 丢包计数

    // BBR相关参数
    private static final int BBR_PROBE_BW_GAIN = 2; // 带宽探测增益
    private static final int BBR_PROBE_RTT_DURATION = 200; // RTT探测持续时间 (ms)
    private static final double BBR_HIGH_GAIN = 2.885; // 慢启动增益
    private static final double BBR_DRAIN_GAIN = 0.35; // 排水增益

    // CUBIC相关参数
    private static final double CUBIC_BETA = 0.7; // CUBIC减少因子
    private static final double CUBIC_C = 0.4; // CUBIC缩放常数
    private long cubicEpochStart = 0; // CUBIC纪元开始时间
    private long cubicLastMaxCwnd = 0; // CUBIC上次最大拥塞窗口
    private long cubicOriginPoint = 0; // CUBIC原点
    
    // 新增：增强拥塞控制参数
    private volatile long lastAdjustTime = System.currentTimeMillis(); // 上次调整时间
    private final AtomicLong totalBytesTransmitted = new AtomicLong(0); // 总传输字节数
    private final AtomicLong totalBytesLost = new AtomicLong(0); // 总丢失字节数
    private volatile double currentBandwidth = 0.0; // 当前带宽 (Mbps)
    private volatile double maxBandwidth = 0.0; // 最大带宽
    private volatile boolean inRecovery = false; // 是否处于恢复模式
    private volatile long recoveryStartTime = 0; // 恢复开始时间
    
    // 新增：智能调节参数
    private static final long RECOVERY_TIMEOUT = 5000; // 恢复超时时间 (5秒)
    private static final double BANDWIDTH_ALPHA = 0.3; // 带宽平滑因子
    private static final double LOSS_RATE_THRESHOLD = 0.02; // 丢包率阈值 (2%)
    private static final int RTT_SPIKE_THRESHOLD = 200; // RTT突增阈值 (ms)

    /**
     * 构造函数
     */
    public QuicCongestionControl(long connectionId) {
        this.connectionId = connectionId;
        this.cubicEpochStart = System.currentTimeMillis();
        this.cubicLastMaxCwnd = congestionWindow.get();
        this.cubicOriginPoint = cubicLastMaxCwnd;
        this.lastAdjustTime = System.currentTimeMillis();

        log.debug("拥塞控制器初始化: connectionId={}, initialCwnd={}",
                connectionId, congestionWindow.get());
    }

    /**
     * 更新RTT测量
     * @param sampleRtt 当前RTT样本 (ms)
     */
    public void updateRtt(long sampleRtt) {
        // 更新最小RTT
        if (sampleRtt < minRtt) {
            minRtt = sampleRtt;
            if (sampleRtt < baseRtt) {
                baseRtt = sampleRtt;
                log.debug("更新基础RTT: connectionId={}, baseRtt={}ms", connectionId, baseRtt);
            }
        }

        // 更新最大RTT
        if (sampleRtt > maxRtt) {
            maxRtt = sampleRtt;
        }

        // 使用指数加权移动平均更新平均RTT
        int sampleCount = rttSampleCount.incrementAndGet();
        if (sampleCount == 1) {
            avgRtt.set(sampleRtt);
            rttVariance = sampleRtt / 2;
        } else {
            // RFC 6298 RTT计算公式
            long rttDiff = Math.abs(sampleRtt - avgRtt.get());
            rttVariance = (3 * rttVariance + rttDiff) / 4;
            avgRtt.set((7 * avgRtt.get() + sampleRtt) / 8);
        }

        log.debug("RTT更新: connectionId={}, sample={}ms, avg={}ms, min={}ms, variance={}ms",
                connectionId, sampleRtt, avgRtt.get(), minRtt, rttVariance);
    }

    /**
     * 发送数据时调用
     * @param bytes 发送的字节数
     */
    public void onDataSent(long bytes) {
        totalBytesTransmitted.addAndGet(bytes);
        lastAdjustTime = System.currentTimeMillis();
        
        // 更新发送速率
        long currentTime = System.currentTimeMillis();
        long timeDiff = currentTime - lastDeliveryTime;

        if (timeDiff > 0) {
            long currentRate = (bytes * 1000) / timeDiff; // bytes/sec
            sendingRate.set((sendingRate.get() + currentRate) / 2); // 平滑处理
            
            // 更新带宽估算 (转换为 Mbps)
            double bandwidthMbps = (currentRate * 8.0) / (1024.0 * 1024.0);
            currentBandwidth = currentBandwidth * (1 - BANDWIDTH_ALPHA) + bandwidthMbps * BANDWIDTH_ALPHA;
            if (currentBandwidth > maxBandwidth) {
                maxBandwidth = currentBandwidth;
            }
        }

        // 智能慢启动控制
        if (inSlowStart && !inRecovery) {
            long currentCwnd = congestionWindow.get();
            long newCwnd = Math.min(currentCwnd + bytes, maxCongestionWindow);
            
            // 检测RTT突增，提前退出慢启动
            if (detectRttSpike()) {
                inSlowStart = false;
                slowStartThreshold.set(newCwnd);
                log.info("检测到RTT突增，提前退出慢启动: connectionId={}, cwnd={}, rtt={}ms", 
                        connectionId, newCwnd, avgRtt.get());
            } else {
                congestionWindow.set(newCwnd);
                
                // 检查是否应该退出慢启动
                if (newCwnd >= slowStartThreshold.get()) {
                    inSlowStart = false;
                    log.info("退出慢启动: connectionId={}, cwnd={}", connectionId, newCwnd);
                }
            }
        } else if (inRecovery) {
            // 恢复模式下缓慢增长
            handleRecoveryMode();
        }

        log.debug("数据发送: connectionId={}, bytes={}, cwnd={}, state={}, bandwidth={}Mbps",
                connectionId, bytes, congestionWindow.get(), getCongestionState(), currentBandwidth);
    }

    /**
     * 收到ACK时调用
     * @param bytes 确认的字节数
     */
    public void onAckReceived(long bytes) {
        // 更新交付速率
        long currentTime = System.currentTimeMillis();
        deliveredBytes += bytes;
        long timeDiff = currentTime - lastDeliveryTime;

        if (timeDiff >= 100) { // 每100ms更新一次速率
            long currentDeliveryRate = (deliveredBytes * 1000) / timeDiff;
            deliveryRate.set((deliveryRate.get() + currentDeliveryRate) / 2);
            deliveredBytes = 0;
            lastDeliveryTime = currentTime;
        }

        // 拥塞避免阶段
        if (!inSlowStart) {
            cubicCongestionAvoidance();
        }

        // BBR拥塞控制
        bbrUpdate();

        log.debug("收到ACK: connectionId={}, bytes={}, cwnd={}, deliveryRate={}",
                connectionId, bytes, congestionWindow.get(), deliveryRate.get());
    }

    /**
     * 发生丢包时调用
     */
    public void onPacketLoss() {
        long currentTime = System.currentTimeMillis();
        lossCount.incrementAndGet();
        lastLossEventTime = currentTime;

        // 进入恢复模式
        inRecovery = true;
        recoveryStartTime = currentTime;

        // 智能拥塞窗口调整
        long currentCwnd = congestionWindow.get();
        long newCwnd;
        
        // 根据丢包率调整窗口减小程度
        double lossRate = getLossRate();
        if (lossRate > LOSS_RATE_THRESHOLD) {
            // 高丢包率，更激进地减小窗口
            newCwnd = Math.max(minCongestionWindow, (long) (currentCwnd * 0.5));
        } else {
            // 低丢包率，温和减小窗口
            newCwnd = Math.max(minCongestionWindow, (long) (currentCwnd * CUBIC_BETA));
        }
        
        congestionWindow.set(newCwnd);
        totalBytesLost.addAndGet(currentCwnd - newCwnd);

        // 更新慢启动阈值
        slowStartThreshold.set(newCwnd);

        // 重置慢启动状态
        inSlowStart = false;

        // CUBIC参数更新
        cubicEpochStart = currentTime;
        cubicLastMaxCwnd = currentCwnd;
        cubicOriginPoint = cubicLastMaxCwnd;

        log.warn("发生丢包: connectionId={}, oldCwnd={}, newCwnd={}, lossCount={}, lossRate={:.3f}",
                connectionId, currentCwnd, newCwnd, lossCount.get(), lossRate);
    }

    /**
     * CUBIC拥塞避免算法
     */
    private void cubicCongestionAvoidance() {
        long currentTime = System.currentTimeMillis();
        long timeSinceEpoch = currentTime - cubicEpochStart;

        // CUBIC函数增长
        double t = timeSinceEpoch / 1000.0; // 转换为秒
        double cubicValue = CUBIC_C * Math.pow(t, 3);

        // 计算目标拥塞窗口
        long targetCwnd = (long) (cubicOriginPoint + cubicValue);
        targetCwnd = Math.min(targetCwnd, maxCongestionWindow);

        // 逐步增长到目标窗口
        long currentCwnd = congestionWindow.get();
        if (targetCwnd > currentCwnd) {
            long increment = (targetCwnd - currentCwnd) / 4; // 平滑增长
            congestionWindow.set(currentCwnd + increment);
        }
    }

    /**
     * BBR算法更新
     */
    private void bbrUpdate() {
        long currentDeliveryRate = deliveryRate.get();
        long currentCwnd = congestionWindow.get();

        // 基于交付速率调整窗口
        if (currentDeliveryRate > 0) {
            long rttBasedCwnd = (currentDeliveryRate * avgRtt.get()) / 1000; // bytes = rate * rtt
            long targetCwnd = Math.max(minCongestionWindow, rttBasedCwnd);

            // BBR增益调整
            if (inSlowStart) {
                targetCwnd = (long) (targetCwnd * BBR_HIGH_GAIN);
            }

            targetCwnd = Math.min(targetCwnd, maxCongestionWindow);

            if (targetCwnd != currentCwnd) {
                congestionWindow.set(targetCwnd);
                log.debug("BBR调整窗口: connectionId={}, oldCwnd={}, newCwnd={}, rate={}",
                        connectionId, currentCwnd, targetCwnd, currentDeliveryRate);
            }
        }
    }

    /**
     * 获取当前拥塞窗口大小
     */
    public long getCongestionWindow() {
        return congestionWindow.get();
    }

    /**
     * 获取发送速率限制 (bytes/sec)
     */
    public long getPacingRate() {
        long cwnd = congestionWindow.get();
        long rtt = avgRtt.get();
        return rtt > 0 ? (cwnd * 1000) / rtt : Long.MAX_VALUE;
    }

    /**
     * 检查是否可以发送指定大小的数据
     */
    public boolean canSend(int dataSize) {
        return dataSize <= congestionWindow.get();
    }

    /**
     * 获取拥塞状态描述
     */
    public String getCongestionState() {
        if (inSlowStart) {
            return "SLOW_START";
        } else {
            return "CONGESTION_AVOIDANCE";
        }
    }

    /**
     * 检测RTT突增
     */
    private boolean detectRttSpike() {
        long currentRtt = avgRtt.get();
        long baseRttValue = baseRtt == Long.MAX_VALUE ? currentRtt : baseRtt;
        return currentRtt - baseRttValue > RTT_SPIKE_THRESHOLD;
    }
    
    /**
     * 处理恢复模式
     */
    private void handleRecoveryMode() {
        long currentTime = System.currentTimeMillis();
        
        // 检查是否应该退出恢复模式
        if (currentTime - recoveryStartTime > RECOVERY_TIMEOUT) {
            inRecovery = false;
            log.info("退出恢复模式: connectionId={}", connectionId);
            return;
        }
        
        // 恢复模式下非常缓慢地增长窗口
        long currentCwnd = congestionWindow.get();
        long newCwnd = Math.min(currentCwnd + 1024, maxCongestionWindow); // 每次增长1KB
        congestionWindow.set(newCwnd);
    }
    
    /**
     * 获取丢包率
     */
    public double getLossRate() {
        long totalTransmitted = totalBytesTransmitted.get();
        long totalLost = totalBytesLost.get();
        return totalTransmitted > 0 ? (double) totalLost / totalTransmitted : 0.0;
    }
    
    /**
     * 获取当前带宽 (Mbps)
     */
    public double getCurrentBandwidth() {
        return currentBandwidth;
    }
    
    /**
     * 获取最大带宽 (Mbps)
     */
    public double getMaxBandwidth() {
        return maxBandwidth;
    }
    


    /**
     * 获取拥塞控制统计信息
     */
    public String getStats() {
        return String.format(
                "QuicCongestionControl{connectionId=%d, state=%s, cwnd=%d, ssthresh=%d, " +
                        "minRtt=%dms, avgRtt=%dms, deliveryRate=%d, pacingRate=%d, lossCount=%d, " +
                        "lossRate=%.3f, bandwidth=%.2fMbps, maxBandwidth=%.2fMbps, totalTransmitted=%d}",
                connectionId, getCongestionState(), congestionWindow.get(), slowStartThreshold.get(),
                minRtt, avgRtt.get(), deliveryRate.get(), getPacingRate(), lossCount.get(),
                getLossRate(), currentBandwidth, maxBandwidth, totalBytesTransmitted.get()
        );
    }

    /**
     * 重置拥塞控制器
     */
    public void reset() {
        congestionWindow.set(10 * 1024);
        slowStartThreshold.set(Long.MAX_VALUE);
        inSlowStart = true;
        inRecovery = false;
        lossCount.set(0);
        sendingRate.set(0);
        deliveryRate.set(0);
        deliveredBytes = 0;
        totalBytesTransmitted.set(0);
        totalBytesLost.set(0);
        currentBandwidth = 0.0;
        maxBandwidth = 0.0;
        lastDeliveryTime = System.currentTimeMillis();
        lastAdjustTime = System.currentTimeMillis();
        recoveryStartTime = 0;
        cubicEpochStart = System.currentTimeMillis();
        cubicLastMaxCwnd = congestionWindow.get();
        cubicOriginPoint = cubicLastMaxCwnd;

        log.info("拥塞控制器重置: connectionId={}", connectionId);
    }
}