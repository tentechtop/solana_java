package com.bit.solana.p2p.quic.control;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 高级流量控制器 - 带宽最大化版本
 * 采用多层令牌桶 + BBR风格拥塞控制 + 自适应调整
 * 目标：压榨网络带宽到极致，同时保证安全稳定
 */
@Slf4j
public class AdvancedFlowController {

    // ========== 核心令牌桶 ==========
    private final long maxBurstSize;        // 最大突发大小
    private final AtomicLong tokenBucket;     // 令牌桶
    private final AtomicLong sendRate;         // 当前发送速率
    private final AtomicLong maxSendRate;      // 最大发送速率
    private final AtomicLong minSendRate;      // 最小发送速率

    // ========== 时间跟踪 ==========
    private volatile long lastRefillTime;

    // ========== BBR风格拥塞控制 ==========
    private volatile long currentRtt;         // 当前RTT (ms)
    private volatile long minRtt;             // 最小RTT (ms)
    private volatile long deliveryRate;        // 传输速率 (bytes/s)
    private volatile long maxDeliveryRate;     // 最大传输速率
    private volatile int congestionState;      // 拥塞状态: 0=启动, 1=探测, 2=传输, 3=拥塞

    // ========== 自适应调整参数 ==========
    private final double growthFactor;        // 增长因子
    private final double decreaseFactor;      // 下降因子
    private final long rttThreshold;         // RTT阈值
    private final long lossThreshold;         // 丢包阈值

    // ========== 性能优化：快速路径 ==========
    private volatile boolean fastPathEnabled = true;
    private final AtomicLong consecutiveSuccess = new AtomicLong(0);
    private final AtomicLong consecutiveFailures = new AtomicLong(0);

    // ========== 统计信息 ==========
    private final AtomicLong totalBytesSent = new AtomicLong(0);
    private final AtomicLong totalBytesDropped = new AtomicLong(0);
    private final AtomicLong adaptationCount = new AtomicLong(0);

    // ========== 控制锁 ==========
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /**
     * 创建高级流量控制器
     * @param initialRate 初始速率
     * @param maxRate 最大速率
     * @param minRate 最小速率
     * @param maxBurst 最大突发
     * @param growthFactor 增长因子 (1.0-1.5)
     * @param decreaseFactor 下降因子 (0.5-0.9)
     */
    public AdvancedFlowController(long initialRate, long maxRate, long minRate,
                                  long maxBurst, double growthFactor, double decreaseFactor) {
        this.maxBurstSize = maxBurst;
        this.maxSendRate = new AtomicLong(maxRate);
        this.minSendRate = new AtomicLong(minRate);
        this.sendRate = new AtomicLong(Math.min(initialRate, maxRate));
        this.tokenBucket = new AtomicLong(maxBurst);
        this.lastRefillTime = System.currentTimeMillis();

        this.growthFactor = Math.min(1.5, Math.max(1.0, growthFactor));
        this.decreaseFactor = Math.min(0.9, Math.max(0.5, decreaseFactor));
        this.rttThreshold = 100; // 100ms
        this.lossThreshold = 3;   // 连续3次失败

        // 初始化拥塞状态
        this.minRtt = Long.MAX_VALUE;
        this.maxDeliveryRate = 0;
        this.congestionState = 0;

        // 预填充令牌桶
        tokenBucket.set(maxBurst);

        log.info("[高级流控] 初始化 - 初始速率:{}MB/s, 最大:{}MB/s, 最小:{}MB/s, 突发:{}MB",
                initialRate / 1024 / 1024, maxRate / 1024 / 1024,
                minRate / 1024 / 1024, maxBurst / 1024 / 1024);
        log.info("[高级流控] 参数 - 增长因子:{}, 下降因子:{}, RTT阈值:{}ms",
                this.growthFactor, this.decreaseFactor, this.rttThreshold);
    }

    /**
     * 快速路径：直接尝试发送（适合连续成功场景）
     */
    public boolean trySendFast(long dataSize) {
        if (!fastPathEnabled || dataSize <= 0 || dataSize > maxBurstSize) {
            return trySend(dataSize);
        }

        // 尝试补充令牌（无锁方式）
        refillTokensFast();

        long currentTokens = tokenBucket.get();
        if (currentTokens >= dataSize) {
            if (tokenBucket.compareAndSet(currentTokens, currentTokens - dataSize)) {
                totalBytesSent.addAndGet(dataSize);
                consecutiveSuccess.incrementAndGet();
                consecutiveFailures.set(0);

                // 连续成功次数达到阈值，自动提升速率
                long successes = consecutiveSuccess.get();
                if (successes % 100 == 0) {
                    autoIncreaseRate();
                }

                return true;
            }
        }

        // 快速路径失败，切换到标准路径
        if (consecutiveFailures.incrementAndGet() > lossThreshold) {
            fastPathEnabled = false;
            consecutiveSuccess.set(0);
            log.debug("[快速路径] 失败次数超限，切换到标准路径");
        }

        // 尝试标准路径（会做完整的令牌补充）
        return trySend(dataSize);
    }

    /**
     * 标准路径：完整的流量控制检查
     */
    public boolean trySend(long dataSize) {
        if (dataSize <= 0) {
            return true;
        }

        lock.readLock().lock();
        try {
            refillTokens();

            long availableTokens = tokenBucket.get();
            if (availableTokens >= dataSize) {
                if (tokenBucket.compareAndSet(availableTokens, availableTokens - dataSize)) {
                    totalBytesSent.addAndGet(dataSize);
                    return true;
                }
            }

            // 令牌不足，尝试自适应调整
            handleTokenShortage(dataSize);

            totalBytesDropped.addAndGet(dataSize);
            return false;

        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 获取发送许可（支持等待）
     */
    public boolean acquire(long dataSize, long maxWaitMs) {
        long startTime = System.currentTimeMillis();

        while (System.currentTimeMillis() - startTime < maxWaitMs) {
            if (trySend(dataSize)) {
                return true;
            }

            long deficit = dataSize - tokenBucket.get();
            if (deficit > 0 && sendRate.get() > 0) {
                long waitTime = (deficit * 1000) / sendRate.get();
                try {
                    Thread.sleep(Math.min(waitTime, 5)); // 短等待
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }

        return false;
    }

    /**
     * 处理令牌短缺 - 自适应调整速率
     */
    private void handleTokenShortage(long dataSize) {
        long failures = consecutiveFailures.incrementAndGet();
        consecutiveSuccess.set(0);

        if (failures > lossThreshold) {
            // 连续多次失败，降低速率
            autoDecreaseRate();
            failures = 0;
        }

        // 如果快速路径已禁用，检查是否可以重新启用
        if (!fastPathEnabled && failures == 0) {
            fastPathEnabled = true;
            log.debug("[快速路径] 重新启用");
        }
    }

    /**
     * 自动增加速率（基于BBR探测）
     */
    private void autoIncreaseRate() {
        lock.writeLock().lock();
        try {
            long currentRate = sendRate.get();
            long maxRate = maxSendRate.get();

            if (currentRate < maxRate) {
                long newRate = (long) Math.min(currentRate * growthFactor, maxRate);
                sendRate.set(newRate);
                adaptationCount.incrementAndGet();

                log.debug("[速率自适应] 提升速率: {} -> {} MB/s ({})",
                        currentRate / 1024 / 1024, newRate / 1024 / 1024,
                        String.format("%.1fx", (double)newRate / currentRate));
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 自动降低速率（拥塞响应）
     */
    private void autoDecreaseRate() {
        lock.writeLock().lock();
        try {
            long currentRate = sendRate.get();
            long minRate = minSendRate.get();

            if (currentRate > minRate) {
                long newRate = (long) Math.max(currentRate * decreaseFactor, minRate);
                sendRate.set(newRate);
                adaptationCount.incrementAndGet();
                consecutiveFailures.set(0);

                log.debug("[速率自适应] 降低速率: {} -> {} MB/s ({})",
                        currentRate / 1024 / 1024, newRate / 1024 / 1024,
                        String.format("%.1fx", (double)newRate / currentRate));
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 更新RTT（BBR风格带宽探测）
     */
    public void updateRtt(long rtt) {
        if (rtt <= 0) return;

        this.currentRtt = rtt;
        if (rtt < minRtt) {
            minRtt = rtt;
        }

        // RTT显著增加，可能是拥塞
        if (rtt > minRtt * 2 && rtt > rttThreshold) {
            // 适度降低速率
            autoDecreaseRate();
        } else if (rtt < minRtt * 1.1 && fastPathEnabled) {
            // RTT接近最小值，可以提升速率
            autoIncreaseRate();
        }
    }

    /**
     * 更新传输速率
     */
    public void updateDeliveryRate(long rate) {
        this.deliveryRate = rate;
        if (rate > maxDeliveryRate) {
            maxDeliveryRate = rate;
        }
    }

    /**
     * 更新发送速率（公共方法）
     * @param newRate 新的发送速率
     */
    public void updateSendRate(long newRate) {
        lock.writeLock().lock();
        try {
            long boundedRate = Math.max(
                    minSendRate.get(),
                    Math.min(newRate, maxSendRate.get())
            );
            sendRate.set(boundedRate);

            log.debug("[速率更新] {} -> {} MB/s",
                    sendRate.get() / 1024 / 1024, boundedRate / 1024 / 1024);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * BBR风格带宽探测
     */
    public void bbrProbe() {
        lock.writeLock().lock();
        try {
            long currentRate = sendRate.get();
            long targetRate = Math.max(deliveryRate, currentRate);

            // 基于传输速率调整
            if (targetRate > currentRate * 1.2) {
                // 传输速率显著高于当前，提升速率
                long newRate = (long) Math.min(currentRate * 1.1, maxSendRate.get());
                sendRate.set(newRate);
                congestionState = 1; // 探测状态
            } else if (targetRate < currentRate * 0.8) {
                // 传输速率低于当前，降低速率
                long newRate = (long) Math.max(currentRate * 0.9, minSendRate.get());
                sendRate.set(newRate);
                congestionState = 3; // 拥塞状态
            } else {
                congestionState = 2; // 传输状态
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 获取最优发送窗口
     */
    public long getOptimalSendWindow() {
        lock.readLock().lock();
        try {
            // 基于RTT计算最优窗口：BDP = 带宽 * RTT
            long bdp = (sendRate.get() * Math.max(currentRtt, 10)) / 1000;

            // 限制在突发大小内
            return Math.min(bdp, maxBurstSize);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 补充令牌
     */
    private void refillTokens() {
        long currentTime = System.currentTimeMillis();
        long timeDiff = currentTime - lastRefillTime;

        if (timeDiff >= 1) {
            long tokensToAdd = (timeDiff * sendRate.get()) / 1000;
            long currentTokens = tokenBucket.get();
            long newTokens = Math.min(currentTokens + tokensToAdd, maxBurstSize);

            if (tokenBucket.compareAndSet(currentTokens, newTokens)) {
                lastRefillTime = currentTime;
            }
        }
    }

    /**
     * 快速补充令牌（无锁方式，用于快速路径）
     */
    private void refillTokensFast() {
        long currentTime = System.currentTimeMillis();
        long timeDiff = currentTime - lastRefillTime;

        if (timeDiff >= 1) {
            long tokensToAdd = (timeDiff * sendRate.get()) / 1000;
            long currentTokens = tokenBucket.get();
            long newTokens = Math.min(currentTokens + tokensToAdd, maxBurstSize);

            // 尝试CAS更新
            if (tokenBucket.compareAndSet(currentTokens, newTokens)) {
                lastRefillTime = currentTime;
            }
        }
    }

    /**
     * 获取当前速率
     */
    public long getSendRate() {
        return sendRate.get();
    }

    /**
     * 获取最大速率
     */
    public long getMaxSendRate() {
        return maxSendRate.get();
    }

    /**
     * 获取最小速率
     */
    public long getMinSendRate() {
        return minSendRate.get();
    }

    /**
     * 获取可用令牌
     */
    public long getAvailableTokens() {
        refillTokens();
        return tokenBucket.get();
    }

    /**
     * 重置控制器
     */
    public void reset() {
        lock.writeLock().lock();
        try {
            tokenBucket.set(maxBurstSize);
            sendRate.set(maxSendRate.get());
            lastRefillTime = System.currentTimeMillis();

            totalBytesSent.set(0);
            totalBytesDropped.set(0);
            adaptationCount.set(0);

            consecutiveSuccess.set(0);
            consecutiveFailures.set(0);
            fastPathEnabled = true;

            minRtt = Long.MAX_VALUE;
            maxDeliveryRate = 0;
            congestionState = 0;

            log.info("[高级流控] 已重置");
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 获取统计信息
     */
    public AdvancedFlowStats getStats() {
        refillTokens();
        return AdvancedFlowStats.builder()
                .totalBytesSent(totalBytesSent.get())
                .totalBytesDropped(totalBytesDropped.get())
                .adaptationCount(adaptationCount.get())
                .currentSendRate(sendRate.get())
                .maxSendRate(maxSendRate.get())
                .minSendRate(minSendRate.get())
                .availableTokens(tokenBucket.get())
                .maxTokens(maxBurstSize)
                .currentRtt(currentRtt)
                .minRtt(minRtt == Long.MAX_VALUE ? 0 : minRtt)
                .deliveryRate(deliveryRate)
                .maxDeliveryRate(maxDeliveryRate)
                .congestionState(congestionState)
                .fastPathEnabled(fastPathEnabled)
                .consecutiveSuccess(consecutiveSuccess.get())
                .consecutiveFailures(consecutiveFailures.get())
                .optimalSendWindow(getOptimalSendWindow())
                .build();
    }
}
