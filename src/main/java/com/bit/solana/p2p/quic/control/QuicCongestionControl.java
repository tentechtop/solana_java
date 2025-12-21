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

    /**
     * 构造函数
     */
    public QuicCongestionControl(long connectionId) {
        this.connectionId = connectionId;
        this.cubicEpochStart = System.currentTimeMillis();
        this.cubicLastMaxCwnd = congestionWindow.get();
        this.cubicOriginPoint = cubicLastMaxCwnd;

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
        // 更新发送速率
        long currentTime = System.currentTimeMillis();
        long timeDiff = currentTime - lastDeliveryTime;

        if (timeDiff > 0) {
            long currentRate = (bytes * 1000) / timeDiff; // bytes/sec
            sendingRate.set((sendingRate.get() + currentRate) / 2); // 平滑处理
        }

        // 慢启动阶段指数增长
        if (inSlowStart) {
            long currentCwnd = congestionWindow.get();
            long newCwnd = Math.min(currentCwnd + bytes, maxCongestionWindow);
            congestionWindow.set(newCwnd);

            // 检查是否应该退出慢启动
            if (newCwnd >= slowStartThreshold.get()) {
                inSlowStart = false;
                log.info("退出慢启动: connectionId={}, cwnd={}", connectionId, newCwnd);
            }
        }

        log.debug("数据发送: connectionId={}, bytes={}, cwnd={}, inSlowStart={}",
                connectionId, bytes, congestionWindow.get(), inSlowStart);
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

        // 减小拥塞窗口
        long currentCwnd = congestionWindow.get();
        long newCwnd = Math.max(minCongestionWindow, (long) (currentCwnd * CUBIC_BETA));
        congestionWindow.set(newCwnd);

        // 更新慢启动阈值
        slowStartThreshold.set(newCwnd);

        // 重置慢启动状态
        inSlowStart = false;

        // CUBIC参数更新
        cubicEpochStart = currentTime;
        cubicLastMaxCwnd = currentCwnd;
        cubicOriginPoint = cubicLastMaxCwnd;

        log.warn("发生丢包: connectionId={}, oldCwnd={}, newCwnd={}, lossCount={}",
                connectionId, currentCwnd, newCwnd, lossCount.get());
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
     * 获取拥塞控制统计信息
     */
    public String getStats() {
        return String.format(
                "QuicCongestionControl{connectionId=%d, state=%s, cwnd=%d, ssthresh=%d, " +
                        "minRtt=%dms, avgRtt=%dms, deliveryRate=%d, pacingRate=%d, lossCount=%d}",
                connectionId, getCongestionState(), congestionWindow.get(), slowStartThreshold.get(),
                minRtt, avgRtt.get(), deliveryRate.get(), getPacingRate(), lossCount.get()
        );
    }

    /**
     * 重置拥塞控制器
     */
    public void reset() {
        congestionWindow.set(10 * 1024);
        slowStartThreshold.set(Long.MAX_VALUE);
        inSlowStart = true;
        lossCount.set(0);
        sendingRate.set(0);
        deliveryRate.set(0);
        deliveredBytes = 0;
        lastDeliveryTime = System.currentTimeMillis();
        cubicEpochStart = System.currentTimeMillis();
        cubicLastMaxCwnd = congestionWindow.get();
        cubicOriginPoint = cubicLastMaxCwnd;

        log.info("拥塞控制器重置: connectionId={}", connectionId);
    }
}