package com.bit.solana.p2p.quic.control;


import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 全局流量控制
 * 管理所有连接的总体流量，确保不超过系统总带宽限制
 */
@Slf4j
public class GlobalFlowControl {

    // 单例实例
    private static volatile GlobalFlowControl instance;

    // 全局流量控制器
    private final FlowController globalFlowController;

    // 每个IP地址的流量统计
    private final ConcurrentHashMap<String, IpFlowStats> ipFlowStatsMap = new ConcurrentHashMap<>();

    // 每个连接的流量配额
    private final ConcurrentHashMap<Long, ConnectionFlowQuota> connectionQuotaMap = new ConcurrentHashMap<>();

    // 全局统计
    private final AtomicLong totalBytesSent = new AtomicLong(0);
    private final AtomicLong totalBytesReceived = new AtomicLong(0);
    private final AtomicLong droppedBytes = new AtomicLong(0);

    // 配置参数
    private long maxGlobalBandwidth = 1024 * 1024 * 100; // 默认100MB/s
    private int maxIpBandwidth = 1024 * 1024 * 10; // 每个IP最大10MB/s
    private int maxConnectionBandwidth = 1024 * 1024 * 5; // 每个连接最大5MB/s
    private int connectionFairShare = 1024 * 1024; // 连接公平份额1MB/s

    private GlobalFlowControl() {
        this.globalFlowController = new FlowController(
                connectionFairShare,  // 初始速率
                maxGlobalBandwidth,   // 最大速率
                maxGlobalBandwidth / 10 // 突发大小
        );

        log.info("[全局流量控制] 初始化完成 - 全局带宽限制:{}MB/s, IP限制:{}MB/s, 连接限制:{}MB/s",
                maxGlobalBandwidth / 1024 / 1024,
                maxIpBandwidth / 1024 / 1024,
                maxConnectionBandwidth / 1024 / 1024);
    }

    public static GlobalFlowControl getInstance() {
        if (instance == null) {
            synchronized (GlobalFlowControl.class) {
                if (instance == null) {
                    instance = new GlobalFlowControl();
                }
            }
        }
        return instance;
    }

    /**
     * 尝试发送数据（全局级别流量控制）
     * @param dataSize 数据大小
     * @return 是否允许发送
     */
    public boolean trySendGlobal(long dataSize) {
        boolean allowed = globalFlowController.trySend(dataSize);

        if (allowed) {
            totalBytesSent.addAndGet(dataSize);
        } else {
            droppedBytes.addAndGet(dataSize);
            log.debug("[全局流控] 数据包被限制 - 大小:{}字节", dataSize);
        }

        return allowed;
    }

    /**
     * 尝试发送数据（IP级别流量控制）
     * @param ipAddress IP地址
     * @param dataSize 数据大小
     * @return 是否允许发送
     */
    public boolean trySendByIp(String ipAddress, long dataSize) {
        IpFlowStats stats = ipFlowStatsMap.computeIfAbsent(
                ipAddress, k -> new IpFlowStats(ipAddress, maxIpBandwidth)
        );

        boolean allowed = stats.trySend(dataSize);

        if (!allowed) {
            log.debug("[IP流控] {} 数据包被限制 - 大小:{}字节", ipAddress, dataSize);
        }

        return allowed;
    }

    /**
     * 尝试发送数据（连接级别流量控制）
     * @param connectionId 连接ID
     * @param dataSize 数据大小
     * @return 是否允许发送
     */
    public boolean trySendByConnection(long connectionId, long dataSize) {
        ConnectionFlowQuota quota = connectionQuotaMap.get(connectionId);

        if (quota == null) {
            // 新连接，分配默认配额
            quota = new ConnectionFlowQuota(connectionId, maxConnectionBandwidth);
            connectionQuotaMap.put(connectionId, quota);
        }

        boolean allowed = quota.trySend(dataSize);

        if (!allowed) {
            log.debug("[连接流控] 连接:{} 数据包被限制 - 大小:{}字节", connectionId, dataSize);
        }

        return allowed;
    }

    /**
     * 综合流量控制检查
     * 必须同时通过全局、IP、连接三个级别的检查
     * @param connectionId 连接ID
     * @param ipAddress IP地址
     * @param dataSize 数据大小
     * @return 是否允许发送
     */
    public FlowControlResult trySend(long connectionId, String ipAddress, long dataSize) {
        // 1. 检查全局流量
        if (!trySendGlobal(dataSize)) {
            return FlowControlResult.rejected("全局流量限制");
        }

        // 2. 检查IP流量
        if (!trySendByIp(ipAddress, dataSize)) {
            // 回滚全局令牌
            globalFlowController.trySend(-dataSize); // 简化处理
            return FlowControlResult.rejected("IP流量限制");
        }

        // 3. 检查连接流量
        if (!trySendByConnection(connectionId, dataSize)) {
            // 回滚IP令牌
            ipFlowStatsMap.get(ipAddress).rollback(dataSize);
            return FlowControlResult.rejected("连接流量限制");
        }

        return FlowControlResult.allowed();
    }

    /**
     * 记录接收数据
     */
    public void recordReceived(long dataSize) {
        totalBytesReceived.addAndGet(dataSize);
    }

    /**
     * 动态调整全局带宽限制
     * @param newBandwidth 新的带宽限制（字节/秒）
     */
    public void updateGlobalBandwidth(long newBandwidth) {
        this.maxGlobalBandwidth = newBandwidth;
        globalFlowController.setMaxSendRate(newBandwidth);

        // 重新计算连接公平份额
        int activeConnections = connectionQuotaMap.size();
        if (activeConnections > 0) {
            long fairShare = newBandwidth / activeConnections;
            connectionFairShare = (int) Math.min(fairShare, maxConnectionBandwidth);
            globalFlowController.updateSendRate(connectionFairShare);
        }

        log.info("[全局流控] 带宽限制更新为:{}MB/s, 连接公平份额:{}MB/s",
                newBandwidth / 1024 / 1024, connectionFairShare / 1024 / 1024);
    }

    /**
     * 移除连接配额
     */
    public void removeConnectionQuota(long connectionId) {
        ConnectionFlowQuota quota = connectionQuotaMap.remove(connectionId);
        if (quota != null) {
            log.debug("[全局流控] 移除连接{}的配额统计 - 已发送:{}字节",
                    connectionId, quota.getTotalBytesSent());
        }
    }

    /**
     * 清理IP统计
     */
    public void cleanupIpStats() {
        long cutoffTime = System.currentTimeMillis() - 60000; // 清理1分钟不活跃的IP

        ipFlowStatsMap.entrySet().removeIf(entry -> {
            IpFlowStats stats = entry.getValue();
            if (stats.getLastActiveTime() < cutoffTime) {
                log.debug("[全局流控] 清理IP统计:{}", entry.getKey());
                return true;
            }
            return false;
        });
    }

    /**
     * 获取全局统计信息
     */
    public GlobalFlowStats getGlobalStats() {
        return GlobalFlowStats.builder()
                .totalBytesSent(totalBytesSent.get())
                .totalBytesReceived(totalBytesReceived.get())
                .droppedBytes(droppedBytes.get())
                .currentGlobalRate(globalFlowController.getCurrentSendRate())
                .maxGlobalRate(globalFlowController.getMaxSendRate())
                .activeConnections(connectionQuotaMap.size())
                .activeIps(ipFlowStatsMap.size())
                .build();
    }

    /**
     * 重置全局统计
     */
    public void resetStats() {
        totalBytesSent.set(0);
        totalBytesReceived.set(0);
        droppedBytes.set(0);
        globalFlowController.reset();

        log.info("[全局流控] 统计信息已重置");
    }

    // ========== 内部类 ==========

    /**
     * IP流量统计
     */
    private static class IpFlowStats {
        private final String ipAddress;
        private final long maxIpBandwidth;
        private final TokenBucket tokenBucket;
        private final AtomicLong totalBytesSent = new AtomicLong(0);
        private volatile long lastActiveTime;

        IpFlowStats(String ipAddress, long maxIpBandwidth) {
            this.ipAddress = ipAddress;
            this.maxIpBandwidth = maxIpBandwidth;
            this.tokenBucket = new TokenBucket(maxIpBandwidth, maxIpBandwidth / 10);
            this.lastActiveTime = System.currentTimeMillis();
        }

        boolean trySend(long dataSize) {
            if (tokenBucket.acquire(dataSize, 10)) {
                totalBytesSent.addAndGet(dataSize);
                lastActiveTime = System.currentTimeMillis();
                return true;
            }
            return false;
        }

        void rollback(long dataSize) {
            tokenBucket.release(dataSize);
        }

        long getLastActiveTime() { return lastActiveTime; }
        long getTotalBytesSent() { return totalBytesSent.get(); }
    }

    /**
     * 连接流量配额
     */
    private static class ConnectionFlowQuota {
        private final long connectionId;
        private final long maxBandwidth;
        private final TokenBucket tokenBucket;
        private final AtomicLong totalBytesSent = new AtomicLong(0);
        private volatile long lastActiveTime;

        ConnectionFlowQuota(long connectionId, long maxBandwidth) {
            this.connectionId = connectionId;
            this.maxBandwidth = maxBandwidth;
            this.tokenBucket = new TokenBucket(maxBandwidth, maxBandwidth / 10);
            this.lastActiveTime = System.currentTimeMillis();
        }

        boolean trySend(long dataSize) {
            if (tokenBucket.acquire(dataSize, 10)) {
                totalBytesSent.addAndGet(dataSize);
                lastActiveTime = System.currentTimeMillis();
                return true;
            }
            return false;
        }

        long getTotalBytesSent() { return totalBytesSent.get(); }
    }

    /**
     * 简化的令牌桶实现
     */
    private static class TokenBucket {
        private final long capacity;
        private final long refillRate; // 每秒补充的字节数
        private volatile long tokens;
        private volatile long lastRefillTime;

        TokenBucket(long capacity, long refillRate) {
            this.capacity = capacity;
            this.refillRate = refillRate;
            this.tokens = capacity;
            this.lastRefillTime = System.currentTimeMillis();
        }

        synchronized boolean acquire(long tokensToAcquire, long maxWaitMs) {
            refillTokens();

            if (tokens >= tokensToAcquire) {
                tokens -= tokensToAcquire;
                return true;
            }

            // 计算需要等待的时间
            long deficit = tokensToAcquire - tokens;
            long waitTime = refillRate > 0 ? (deficit * 1000) / refillRate : maxWaitMs + 1;

            if (waitTime <= maxWaitMs) {
                tokens = 0;
                return true;
            }

            return false;
        }

        void release(long tokensToRelease) {
            synchronized (this) {
                tokens = Math.min(capacity, tokens + tokensToRelease);
            }
        }

        private void refillTokens() {
            long currentTime = System.currentTimeMillis();
            long timeDiff = currentTime - lastRefillTime;

            if (timeDiff > 0 && refillRate > 0) {
                long tokensToAdd = (timeDiff * refillRate) / 1000;
                tokens = Math.min(capacity, tokens + tokensToAdd);
                lastRefillTime = currentTime;
            }
        }
    }

    /**
     * 流量控制结果
     */
    public static class FlowControlResult {
        private final boolean allowed;
        private final String reason;

        private FlowControlResult(boolean allowed, String reason) {
            this.allowed = allowed;
            this.reason = reason;
        }

        static FlowControlResult allowed() {
            return new FlowControlResult(true, "允许");
        }

        static FlowControlResult rejected(String reason) {
            return new FlowControlResult(false, reason);
        }

        public boolean isAllowed() { return allowed; }
        public String getReason() { return reason; }
    }

    /**
     * 全局流量统计
     */
    @lombok.Builder
    @lombok.Data
    public static class GlobalFlowStats {
        private long totalBytesSent;
        private long totalBytesReceived;
        private long droppedBytes;
        private long currentGlobalRate;
        private long maxGlobalRate;
        private int activeConnections;
        private int activeIps;
    }
}
