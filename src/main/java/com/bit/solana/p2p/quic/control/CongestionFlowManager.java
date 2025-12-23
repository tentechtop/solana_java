package com.bit.solana.p2p.quic.control;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 集成的拥塞控制和流量管理器
 * 协调拥塞控制算法和流量控制器
 */
@Slf4j
public class CongestionFlowManager {
    
    // 核心组件
    private final CongestionControl congestionControl;
    private final FlowController flowController;
    
    // 调度器
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean running;
    
    // 监控和统计
    private volatile long lastMetricsTime;
    private long totalPacketsSent;
    private long totalPacketsAcked;
    private long totalPacketsLost;
    
    public CongestionFlowManager() {
        // 初始化CUBIC拥塞控制
        this.congestionControl = new CubicCongestionControl();
        
        // 初始化流量控制器（初始5MB/s，最大10MB/s，突发5MB）
        this.flowController = new FlowController(
            5 * 1024 * 1024,   // 5MB/s初始速率
            10 * 1024 * 1024,  // 10MB/s最大速率
            5 * 1024 * 1024    // 5MB突发
        );
        
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "CongestionFlowManager");
            t.setDaemon(true);
            return t;
        });
        
        this.running = new AtomicBoolean(false);
        this.lastMetricsTime = System.currentTimeMillis();
        
        // 启动定期更新任务
        startPeriodicUpdates();
    }
    
    /**
     * 启动管理器
     */
    public void start() {
        if (running.compareAndSet(false, true)) {
            log.info("[拥塞流控管理器启动]");
        }
    }
    
    /**
     * 停止管理器
     */
    public void shutdown() {
        if (running.compareAndSet(true, false)) {
            scheduler.shutdown();
            flowController.shutdown();
            
            try {
                if (!scheduler.awaitTermination(3, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            
            log.info("[拥塞流控管理器关闭]");
        }
    }
    
    /**
     * 尝试发送数据
     * @param dataSize 数据大小
     * @return 是否可以发送
     */
    public boolean canSend(long dataSize) {
        if (!running.get()) {
            return false;
        }
        
        // 检查拥塞控制
        if (!congestionControl.canSend(dataSize)) {
            return false;
        }
        
        // 检查流量控制
        return flowController.trySend(dataSize);
    }
    
    /**
     * 获取发送许可（阻塞式）
     * @param dataSize 数据大小
     * @param timeoutMs 超时时间（毫秒）
     * @return 是否获得许可
     */
    public boolean acquireSendPermission(long dataSize, long timeoutMs) {
        if (!running.get()) {
            return false;
        }
        
        return flowController.acquireSendPermission(dataSize, timeoutMs);
    }
    
    /**
     * 处理ACK确认
     * @param ackedBytes 确认字节数
     * @param rtt 往返时间
     */
    public void onAck(long ackedBytes, long rtt) {
        if (!running.get()) {
            return;
        }
        
        synchronized (this) {
            totalPacketsAcked++;
            congestionControl.onAck(ackedBytes, rtt);
            
            // 根据拥塞控制结果更新流量控制
            long targetRate = congestionControl.getSendRate();
            flowController.updateSendRate(targetRate);
            
            log.debug("[ACK处理] 确认:{}B, RTT:{}ms, 新速率:{}B/s", 
                    ackedBytes, rtt, targetRate);
        }
    }
    
    /**
     * 处理数据包丢失
     * @param lostBytes 丢失字节数
     */
    public void onPacketLoss(long lostBytes) {
        if (!running.get()) {
            return;
        }
        
        synchronized (this) {
            totalPacketsLost++;
            congestionControl.onPacketLoss(lostBytes);
            
            // 根据拥塞控制结果更新流量控制
            long targetRate = congestionControl.getSendRate();
            flowController.updateSendRate(targetRate);
            
            log.warn("[丢包处理] 丢失:{}B, 新速率:{}B/s", lostBytes, targetRate);
        }
    }
    
    /**
     * 记录数据包发送
     * @param packetSize 数据包大小
     */
    public void recordPacketSent(long packetSize) {
        totalPacketsSent++;
        
        // 更新拥塞控制
        congestionControl.updateSendRate();
    }
    
    /**
     * 设置最大发送速率
     * @param maxRate 最大速率（字节/秒）
     */
    public void setMaxSendRate(long maxRate) {
        congestionControl.setMaxSendRate(maxRate);
        flowController.setMaxSendRate(maxRate);
        log.info("[最大速率设置] {}B/s", maxRate);
    }
    
    /**
     * 获取当前拥塞窗口
     */
    public long getCongestionWindow() {
        return congestionControl.getCongestionWindow();
    }
    
    /**
     * 获取当前发送速率
     */
    public long getCurrentSendRate() {
        return flowController.getCurrentSendRate();
    }
    
    /**
     * 获取综合状态报告
     */
    public String getComprehensiveReport() {
        CongestionState congestionState = congestionControl.getState();
        FlowControlStats flowStats = flowController.getStats();
        
        return String.format(
            "=== 拥塞流控状态报告 ===\n" +
            "拥塞窗口: %s | 发送速率: %s | RTT: %dms\n" +
            "慢启动: %s | 拥塞避免: %s | 恢复: %s\n" +
            "丢包率: %.2f%% | 吞吐量: %s\n" +
            "令牌利用率: %.1f%% | 速率利用率: %.1f%%\n" +
            "统计: 发送=%d, 确认=%d, 丢失=%d",
            
            formatBytes(congestionState.getCongestionWindow()),
            formatBytes(congestionState.getCurrentSendRate()),
            congestionState.getSmoothedRtt(),
            
            congestionState.isInSlowStart() ? "是" : "否",
            congestionState.isInCongestionAvoidance() ? "是" : "否",
            congestionState.isInRecovery() ? "是" : "否",
            
            congestionState.getPacketLossRate() * 100,
            formatBytes((long) congestionState.getAverageThroughput()),
            
            flowStats.getTokenUtilization() * 100,
            flowStats.getRateUtilization() * 100,
            
            totalPacketsSent,
            totalPacketsAcked,
            totalPacketsLost
        );
    }
    
    /**
     * 启动定期更新任务
     */
    private void startPeriodicUpdates() {
        // 每秒更新一次拥塞控制状态
        scheduler.scheduleAtFixedRate(() -> {
            if (running.get()) {
                try {
                    congestionControl.updateSendRate();
                    
                    // 定期输出状态报告（每30秒）
                    long currentTime = System.currentTimeMillis();
                    if (currentTime - lastMetricsTime > 30000) {
                        log.info(getComprehensiveReport());
                        lastMetricsTime = currentTime;
                    }
                    
                } catch (Exception e) {
                    log.error("[定期更新异常]", e);
                }
            }
        }, 1, 1, TimeUnit.SECONDS);
        
        // 每5秒检查恢复状态
        scheduler.scheduleAtFixedRate(() -> {
            if (running.get() && congestionControl instanceof CubicCongestionControl) {
                ((CubicCongestionControl) congestionControl).checkRecoveryEnd();
            }
        }, 5, 5, TimeUnit.SECONDS);
    }
    
    /**
     * 重置管理器状态
     */
    public void reset() {
        synchronized (this) {
            congestionControl.reset();
            flowController.reset();
            
            totalPacketsSent = 0;
            totalPacketsAcked = 0;
            totalPacketsLost = 0;
            lastMetricsTime = System.currentTimeMillis();
            
            log.info("[管理器重置]");
        }
    }
    
    /**
     * 格式化字节大小
     */
    private String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + "B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.1fKB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.1fMB", bytes / (1024.0 * 1024));
        } else {
            return String.format("%.1fGB", bytes / (1024.0 * 1024 * 1024));
        }
    }
    
    /**
     * 检查管理器是否运行
     */
    public boolean isRunning() {
        return running.get();
    }
}