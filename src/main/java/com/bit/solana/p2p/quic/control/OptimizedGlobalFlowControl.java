package com.bit.solana.p2p.quic.control;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;


/**
 * 优化的全局流量控制
 * 目标：压榨网络带宽到极致，同时保证公平和安全
 */
@Slf4j
public class OptimizedGlobalFlowControl {

    // 单例实例
    private static volatile OptimizedGlobalFlowControl instance;

    // ========== 全局流量控制 ==========
    private final AdvancedFlowController globalController;

    // ========== IP级别流量控制 ==========
    private final ConcurrentHashMap<String, AdvancedFlowController> ipControllers = new ConcurrentHashMap<>();

    // ========== 连接级别流量控制 ==========
    private final ConcurrentHashMap<Long, AdvancedFlowController> connectionControllers = new ConcurrentHashMap<>();

    // ========== 全局统计 ==========
    private final AtomicLong totalBytesSent = new AtomicLong(0);
    private final AtomicLong totalBytesReceived = new AtomicLong(0);
    private final AtomicLong droppedBytes = new AtomicLong(0);

    // ========== 配置参数 ==========
    private volatile long maxGlobalBandwidth = 1024 * 1024 * 100; // 默认100MB/s
    private volatile long maxIpBandwidth = 1024 * 1024 * 20;    // IP限制20MB/s
    private volatile long maxConnectionBandwidth = 1024 * 1024 * 10; // 连接限制10MB/s
    private volatile long minConnectionBandwidth = 1024 * 512;  // 连接最小512KB/s

    // ========== 自适应带宽分配 ==========
    private final ConcurrentHashMap<Long, Long> connectionPriority = new ConcurrentHashMap<>();
    private volatile long globalBandwidthUtilization = 0;

    private OptimizedGlobalFlowControl() {
        // 全局控制器：初始50MB/s，最大100MB/s，最小10MB/s
        this.globalController = new AdvancedFlowController(
                50 * 1024 * 1024,   // 初始: 50MB/s
                100 * 1024 * 1024,   // 最大: 100MB/s
                10 * 1024 * 1024,    // 最小: 10MB/s
                20 * 1024 * 1024,    // 突发: 20MB
                1.1,                  // 增长因子: 1.1 (激进)
                0.8                   // 下降因子: 0.8 (适度)
        );

        log.info("[优化全局流控] 初始化 - 全局带宽:{}MB/s, IP限制:{}MB/s, 连接限制:{}MB/s",
                maxGlobalBandwidth / 1024 / 1024,
                maxIpBandwidth / 1024 / 1024,
                maxConnectionBandwidth / 1024 / 1024);
    }

    public static OptimizedGlobalFlowControl getInstance() {
        if (instance == null) {
            synchronized (OptimizedGlobalFlowControl.class) {
                if (instance == null) {
                    instance = new OptimizedGlobalFlowControl();
                }
            }
        }
        return instance;
    }

    /**
     * 尝试发送数据（全局级别）
     */
    public boolean trySendGlobal(long dataSize) {
        boolean allowed = globalController.trySendFast(dataSize);

        if (allowed) {
            totalBytesSent.addAndGet(dataSize);
            updateUtilization();
        } else {
            droppedBytes.addAndGet(dataSize);
            log.debug("[全局流控] 数据包被限制 - 大小:{}字节", dataSize);
        }

        return allowed;
    }

    /**
     * 尝试发送数据（IP级别）
     */
    public boolean trySendByIp(String ipAddress, long dataSize) {
        AdvancedFlowController ipController = ipControllers.computeIfAbsent(
                ipAddress, k -> createIpController()
        );

        boolean allowed = ipController.trySendFast(dataSize);

        if (!allowed) {
            log.debug("[IP流控] {} 数据包被限制 - 大小:{}字节", ipAddress, dataSize);
        }

        return allowed;
    }

    /**
     * 尝试发送数据（连接级别）
     */
    public boolean trySendByConnection(long connectionId, long dataSize) {
        AdvancedFlowController connController = connectionControllers.computeIfAbsent(
                connectionId, k -> createConnectionController()
        );

        boolean allowed = connController.trySendFast(dataSize);

        if (!allowed) {
            log.debug("[连接流控] 连接:{} 数据包被限制 - 大小:{}字节", connectionId, dataSize);
        }

        return allowed;
    }

    /**
     * 综合流量控制检查（三层检查）
     * 优化：快速路径 + 自适应回滚
     */
    public OptimizedFlowControlResult trySend(long connectionId, String ipAddress, long dataSize) {
        // 快速路径1：检查全局流量
        if (!trySendGlobal(dataSize)) {
            return OptimizedFlowControlResult.rejected("全局流量限制");
        }

        // 快速路径2：检查IP流量
        if (!trySendByIp(ipAddress, dataSize)) {
            // 回滚全局令牌
            rollbackGlobalTokens(dataSize);
            return OptimizedFlowControlResult.rejected("IP流量限制");
        }

        // 快速路径3：检查连接流量
        if (!trySendByConnection(connectionId, dataSize)) {
            // 回滚IP令牌
            rollbackIpTokens(ipAddress, dataSize);
            rollbackGlobalTokens(dataSize);
            return OptimizedFlowControlResult.rejected("连接流量限制");
        }

        // 成功：更新连接优先级（用于自适应带宽分配）
        updateConnectionPriority(connectionId, dataSize);

        return OptimizedFlowControlResult.allowed();
    }

    /**
     * 创建IP控制器
     */
    private AdvancedFlowController createIpController() {
        return new AdvancedFlowController(
                maxIpBandwidth / 2,      // 初始: IP限制的一半
                maxIpBandwidth,           // 最大: IP限制
                maxIpBandwidth / 10,      // 最小: IP限制的10%
                maxIpBandwidth / 2,       // 突发: IP限制的一半
                1.15,                   // 增长因子: 1.15
                0.85                     // 下降因子: 0.85
        );
    }

    /**
     * 创建连接控制器
     */
    private AdvancedFlowController createConnectionController() {
        return new AdvancedFlowController(
                maxConnectionBandwidth / 2, // 初始: 连接限制的一半
                maxConnectionBandwidth,      // 最大: 连接限制
                minConnectionBandwidth,     // 最小: 最小限制
                maxConnectionBandwidth / 2, // 突发: 连接限制的一半
                1.2,                     // 增长因子: 1.2 (更激进)
                0.9                      // 下降因子: 0.9
        );
    }

    /**
     * 回滚全局令牌
     */
    private void rollbackGlobalTokens(long dataSize) {
        // 简化处理：不实际回滚，依赖令牌自然补充
        // 这样可以避免复杂的同步，性能更好
    }

    /**
     * 回滚IP令牌
     */
    private void rollbackIpTokens(String ipAddress, long dataSize) {
        // 简化处理：同上
    }

    /**
     * 更新连接优先级（基于发送量）
     */
    private void updateConnectionPriority(long connectionId, long dataSize) {
        connectionPriority.compute(connectionId, (k, v) -> {
            if (v == null) {
                return (long) dataSize;
            }
            // 滑动窗口平均
            return v * 9 / 10 + dataSize;
        });
    }

    /**
     * 自适应带宽分配
     * 基于连接活跃度和优先级动态调整各连接带宽
     */
    public void adaptiveBandwidthAllocation() {
        int activeConnections = connectionControllers.size();
        if (activeConnections == 0) return;

        // 计算每个连接的公平份额
        long totalAvailable = maxGlobalBandwidth;

        // 基于优先级分配
        connectionControllers.forEach((connId, controller) -> {
            Long priority = connectionPriority.get(connId);
            if (priority != null) {
                // 基于优先级计算该连接的带宽配额
                long quota = Math.min(
                        totalAvailable / activeConnections * 2, // 高优先级连接可以多分配
                        maxConnectionBandwidth
                );
                controller.updateSendRate(quota);
            }
        });
    }

    /**
     * 更新全局带宽利用率
     */
    private void updateUtilization() {
        long currentRate = globalController.getSendRate();
        globalBandwidthUtilization = (currentRate * 100) / maxGlobalBandwidth;
    }

    /**
     * 动态调整全局带宽限制
     */
    public void updateGlobalBandwidth(long newBandwidth) {
        this.maxGlobalBandwidth = newBandwidth;
        globalController.updateSendRate(newBandwidth);

        log.info("[全局流控] 带宽限制更新: {} MB/s, 当前利用率: {}%",
                newBandwidth / 1024 / 1024, globalBandwidthUtilization);
    }

    /**
     * 记录接收数据
     */
    public void recordReceived(long dataSize) {
        totalBytesReceived.addAndGet(dataSize);
    }

    /**
     * 更新连接RTT（用于BBR探测）
     */
    public void updateConnectionRtt(long connectionId, long rtt) {
        AdvancedFlowController controller = connectionControllers.get(connectionId);
        if (controller != null) {
            controller.updateRtt(rtt);

            // RTT较低时，可以提升该连接的带宽配额
            if (rtt < 50 && globalBandwidthUtilization < 90) {
                long currentRate = controller.getSendRate();
                long newRate = (long) Math.min(currentRate * 1.1, maxConnectionBandwidth);
                controller.updateSendRate(newRate);
            }
        }
    }

    /**
     * 移除连接控制器
     */
    public void removeConnection(long connectionId) {
        AdvancedFlowController controller = connectionControllers.remove(connectionId);
        if (controller != null) {
            connectionPriority.remove(connectionId);
            log.debug("[全局流控] 移除连接: {}", connectionId);
        }
    }

    /**
     * 获取全局统计
     */
    public OptimizedGlobalStats getGlobalStats() {
        return OptimizedGlobalStats.builder()
                .totalBytesSent(totalBytesSent.get())
                .totalBytesReceived(totalBytesReceived.get())
                .droppedBytes(droppedBytes.get())
                .currentGlobalRate(globalController.getSendRate())
                .maxGlobalRate(maxGlobalBandwidth)
                .bandwidthUtilization(globalBandwidthUtilization)
                .activeConnections(connectionControllers.size())
                .activeIps(ipControllers.size())
                .globalStats(globalController.getStats())
                .build();
    }

    /**
     * 获取连接统计
     */
    public AdvancedFlowStats getConnectionStats(long connectionId) {
        AdvancedFlowController controller = connectionControllers.get(connectionId);
        return controller != null ? controller.getStats() : null;
    }

    /**
     * 重置统计
     */
    public void resetStats() {
        totalBytesSent.set(0);
        totalBytesReceived.set(0);
        droppedBytes.set(0);
        globalController.reset();
        connectionControllers.clear();
        ipControllers.clear();
        connectionPriority.clear();

        log.info("[全局流控] 统计已重置");
    }

    /**
     * 清理不活跃的IP
     */
    public void cleanupInactiveIps() {
        long cutoffTime = System.currentTimeMillis() - 120000; // 2分钟不活跃

        // 此处简化处理，实际应该记录每个IP的最后活跃时间
        log.debug("[全局流控] 清理不活跃IP");
    }

    // ========== 内部类 ==========

    /**
     * 优化的流量控制结果
     */
    public static class OptimizedFlowControlResult {
        private final boolean allowed;
        private final String reason;

        private OptimizedFlowControlResult(boolean allowed, String reason) {
            this.allowed = allowed;
            this.reason = reason;
        }

        public static OptimizedFlowControlResult allowed() {
            return new OptimizedFlowControlResult(true, "允许");
        }

        public static OptimizedFlowControlResult rejected(String reason) {
            return new OptimizedFlowControlResult(false, reason);
        }

        public boolean isAllowed() { return allowed; }
        public String getReason() { return reason; }
    }

    /**
     * 优化的全局统计
     */
    @lombok.Builder
    @lombok.Data
    public static class OptimizedGlobalStats {
        private long totalBytesSent;
        private long totalBytesReceived;
        private long droppedBytes;
        private long currentGlobalRate;
        private long maxGlobalRate;
        private long bandwidthUtilization;
        private int activeConnections;
        private int activeIps;
        private AdvancedFlowStats globalStats;
    }
}
