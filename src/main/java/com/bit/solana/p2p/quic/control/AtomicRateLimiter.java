package com.bit.solana.p2p.quic.control;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 原子速率限制器
 * 实现基于令牌桶算法的速率控制
 */
public class AtomicRateLimiter {
    private final long maxTokens;
    private final AtomicLong tokens;
    private final AtomicLong lastRefillTime;
    private final AtomicBoolean isInitialized;
    
    /**
     * 构造函数
     * @param tokensPerSecond 每秒生成的令牌数
     */
    public AtomicRateLimiter(long tokensPerSecond) {
        this.maxTokens = tokensPerSecond;
        this.tokens = new AtomicLong(tokensPerSecond); // 初始满令牌
        this.lastRefillTime = new AtomicLong(System.currentTimeMillis());
        this.isInitialized = new AtomicBoolean(false);
    }
    
    /**
     * 尝试获取指定数量的令牌
     * @param tokenCount 令牌数量
     * @return true=获取成功，false=获取失败
     */
    public boolean tryAcquire(long tokenCount) {
        refillTokens();
        
        long currentTokens = tokens.get();
        while (currentTokens >= tokenCount) {
            if (tokens.compareAndSet(currentTokens, currentTokens - tokenCount)) {
                return true;
            }
            currentTokens = tokens.get();
        }
        
        return false;
    }
    
    /**
     * 阻塞获取令牌
     * @param tokenCount 令牌数量
     */
    public void acquire(long tokenCount) throws InterruptedException {
        while (!tryAcquire(tokenCount)) {
            Thread.sleep(10); // 等待10ms后重试
        }
    }
    
    /**
     * 非阻塞获取指定数量的令牌
     * @param tokenCount 令牌数量
     * @return 实际获取到的令牌数量
     */
    public long acquireNonBlocking(long tokenCount) {
        refillTokens();
        
        long currentTokens = tokens.get();
        long actualTokens = Math.min(currentTokens, tokenCount);
        
        if (actualTokens > 0 && tokens.compareAndSet(currentTokens, currentTokens - actualTokens)) {
            return actualTokens;
        }
        
        return 0;
    }
    
    /**
     * 补充令牌
     */
    private void refillTokens() {
        long now = System.currentTimeMillis();
        long lastTime = lastRefillTime.get();
        
        if (now > lastTime) {
            long timeDiffMs = now - lastTime;
            double tokensToAdd = (timeDiffMs / 1000.0) * maxTokens;
            
            long currentTokens = tokens.get();
            long newTokens = Math.min(maxTokens, (long) (currentTokens + tokensToAdd));
            
            if (tokens.compareAndSet(currentTokens, newTokens)) {
                lastRefillTime.compareAndSet(lastTime, now);
            }
        }
    }
    
    /**
     * 获取当前可用令牌数
     */
    public long getAvailableTokens() {
        refillTokens();
        return tokens.get();
    }
    
    /**
     * 检查是否可以获取指定数量的令牌
     */
    public boolean canAcquire(long tokenCount) {
        refillTokens();
        return tokens.get() >= tokenCount;
    }
    
    /**
     * 重置令牌桶
     */
    public void reset() {
        tokens.set(maxTokens);
        lastRefillTime.set(System.currentTimeMillis());
    }
    
    /**
     * 获取最大令牌数
     */
    public long getMaxTokens() {
        return maxTokens;
    }
}