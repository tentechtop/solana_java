package com.bit.solana.p2p.quic.control;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 流量控制器
 * 基于令牌桶算法实现流量整形和速率限制
 */
@Slf4j
public class FlowController {
    
    // 令牌桶参数
    private final long maxBurstSize;        // 最大突发大小（字节）
    private final AtomicLong tokenBucket;     // 令牌桶容量
    private final AtomicLong sendRate;         // 发送速率（字节/秒）
    private final AtomicLong maxSendRate;      // 最大发送速率（字节/秒）
    
    // 时间跟踪
    private volatile long lastRefillTime;     // 上次补充令牌时间
    
    // 控制锁
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    
    // 统计信息
    private final AtomicLong totalBytesSent = new AtomicLong(0);
    private final AtomicLong totalBytesDropped = new AtomicLong(0);
    private final AtomicLong tokensExhaustedCount = new AtomicLong(0);
    
    public FlowController(long initialRate, long maxRate, long maxBurst) {
        this.maxBurstSize = maxBurst;
        this.maxSendRate = new AtomicLong(maxRate);
        this.sendRate = new AtomicLong(Math.min(initialRate, maxRate));
        this.tokenBucket = new AtomicLong(maxBurst);
        this.lastRefillTime = System.currentTimeMillis();
        
        // 预填充令牌桶
        tokenBucket.set(maxBurst);
        
        log.info("[流量控制器初始化] 初始速率:{}B/s, 最大速率:{}B/s, 突发大小:{}B", 
                initialRate, maxRate, maxBurst);
    }
    
    /**
     * 尝试发送指定大小的数据
     * @param dataSize 数据大小
     * @return 是否可以发送
     */
    public boolean trySend(long dataSize) {
        if (dataSize <= 0) {
            return true;
        }
        
        lock.readLock().lock();
        try {
            // 补充令牌
            refillTokens();
            
            // 检查是否有足够令牌
            long availableTokens = tokenBucket.get();
            if (availableTokens >= dataSize) {
                if (tokenBucket.compareAndSet(availableTokens, availableTokens - dataSize)) {
                    totalBytesSent.addAndGet(dataSize);
                    return true;
                }
            }
            
            // 令牌不足，统计丢弃
            totalBytesDropped.addAndGet(dataSize);
            tokensExhaustedCount.incrementAndGet();
            return false;
            
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * 获取发送许可（阻塞直到有足够令牌）
     * @param dataSize 数据大小
     * @param maxWaitTime 最大等待时间（毫秒）
     * @return 是否获得许可
     */
    public boolean acquireSendPermission(long dataSize, long maxWaitTime) {
        long startTime = System.currentTimeMillis();
        
        while (System.currentTimeMillis() - startTime < maxWaitTime) {
            if (trySend(dataSize)) {
                return true;
            }
            
            // 计算需要等待的时间
            refillTokens();
            long deficit = dataSize - tokenBucket.get();
            if (deficit > 0) {
                long waitTime = (deficit * 1000) / sendRate.get();
                if (waitTime > 0) {
                    try {
                        Thread.sleep(Math.min(waitTime, 10)); // 最多等待10ms
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }
        }
        
        return false;
    }
    
    /**
     * 更新发送速率
     * @param newRate 新速率（字节/秒）
     */
    public void updateSendRate(long newRate) {
        lock.writeLock().lock();
        try {
            long rate = Math.max(1024, Math.min(newRate, maxSendRate.get()));
            sendRate.set(rate);
            log.debug("[速率更新] 新速率:{}B/s, 令牌桶:{}", rate, tokenBucket.get());
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * 设置最大发送速率
     * @param maxRate 最大速率（字节/秒）
     */
    public void setMaxSendRate(long maxRate) {
        maxSendRate.set(maxRate);
        updateSendRate(Math.min(sendRate.get(), maxRate));
    }
    
    /**
     * 补充令牌
     */
    private void refillTokens() {
        long currentTime = System.currentTimeMillis();
        long timeDiff = currentTime - lastRefillTime;
        
        if (timeDiff >= 1) { // 至少1ms才补充
            long tokensToAdd = (timeDiff * sendRate.get()) / 1000;
            long currentTokens = tokenBucket.get();
            long newTokens = Math.min(currentTokens + tokensToAdd, maxBurstSize);
            
            if (tokenBucket.compareAndSet(currentTokens, newTokens)) {
                lastRefillTime = currentTime;
            }
        }
    }
    
    /**
     * 获取当前发送速率
     */
    public long getCurrentSendRate() {
        return sendRate.get();
    }
    
    /**
     * 获取最大发送速率
     */
    public long getMaxSendRate() {
        return maxSendRate.get();
    }
    
    /**
     * 获取可用令牌数量
     */
    public long getAvailableTokens() {
        refillTokens();
        return tokenBucket.get();
    }
    
    /**
     * 获取令牌桶利用率（0-1）
     */
    public double getTokenUtilization() {
        return (double) getAvailableTokens() / maxBurstSize;
    }
    
    /**
     * 重置流量控制器
     */
    public void reset() {
        lock.writeLock().lock();
        try {
            tokenBucket.set(maxBurstSize);
            sendRate.set(maxSendRate.get());
            lastRefillTime = System.currentTimeMillis();
            
            totalBytesSent.set(0);
            totalBytesDropped.set(0);
            tokensExhaustedCount.set(0);
            
            log.info("[流量控制器重置]");
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * 获取统计信息
     */
    public FlowControlStats getStats() {
        refillTokens();
        return FlowControlStats.builder()
                .totalBytesSent(totalBytesSent.get())
                .totalBytesDropped(totalBytesDropped.get())
                .tokensExhaustedCount(tokensExhaustedCount.get())
                .currentSendRate(sendRate.get())
                .maxSendRate(maxSendRate.get())
                .availableTokens(tokenBucket.get())
                .maxTokens(maxBurstSize)
                .tokenUtilization(getTokenUtilization())
                .build();
    }
    
    /**
     * 关闭流量控制器，清理资源
     */
    public void shutdown() {
        lock.writeLock().lock();
        try {
            sendRate.set(0);
            tokenBucket.set(0);
            log.info("[流量控制器关闭]");
        } finally {
            lock.writeLock().unlock();
        }
    }
}