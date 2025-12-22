package com.bit.solana.p2p.quic.control;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 全局流量控制器
 * 管理所有连接的总发送流量，防止全局发送溢出
 * 配置为每秒15MB的流量控制
 */
@Slf4j
public class GlobalFlowControl {
    // 全局最大总在途字节数（基于每秒15MB的设计）
    private final long GLOBAL_MAX_IN_FLIGHT_BYTES = 15 * 1024 * 1024;
    
    // 每秒目标流量控制：15MB
    private static final long TARGET_BYTES_PER_SECOND = 15 * 1024 * 1024;
    
    // 流量控制窗口大小（1秒的流量）
    private static final long FLOW_CONTROL_WINDOW = TARGET_BYTES_PER_SECOND;
    // 全局总在途字节数（所有连接的在途字节总和）
    private final AtomicLong globalBytesInFlight = new AtomicLong(0);
    // 活跃连接集合（连接ID -> 流量控制器）
    private final ConcurrentMap<Long, QuicFlowControl> activeConnections = new ConcurrentHashMap<>();

    // 新增：全局统计信息
    private final AtomicLong totalBytesSent = new AtomicLong(0); // 全局总发送字节数
    private final AtomicLong totalBytesReceived = new AtomicLong(0); // 全局总接收字节数
    private final AtomicLong totalConnectionsCreated = new AtomicLong(0); // 总创建连接数
    private final AtomicLong totalConnectionsClosed = new AtomicLong(0); // 总关闭连接数
    private final AtomicLong peakBytesInFlight = new AtomicLong(0); // 峰值在途字节数
    private volatile long startTime = System.currentTimeMillis(); // 启动时间
    
    // 流量控制相关
    private final AtomicLong currentSecondBytes = new AtomicLong(0); // 当前秒内发送字节数
    private volatile long currentSecondStart = System.currentTimeMillis(); // 当前秒开始时间
    private final AtomicLong totalSeconds = new AtomicLong(0); // 总秒数统计
    
    // 速率控制
    private final AtomicRateLimiter rateLimiter = new AtomicRateLimiter(TARGET_BYTES_PER_SECOND);
    
    // 新增：连接管理
    private final ConcurrentMap<Long, ConnectionInfo> connectionInfos = new ConcurrentHashMap<>();
    
    // 单例实例
    private static final GlobalFlowControl INSTANCE = new GlobalFlowControl();

    private GlobalFlowControl() {
        // 定期清理非活跃连接
        startConnectionCleanupTask();
    }

    public static GlobalFlowControl getInstance() {
        return INSTANCE;
    }

    /**
     * 注册新连接
     */
    public void registerConnection(QuicFlowControl flowControl) {
        activeConnections.put(flowControl.getConnectionId(), flowControl);
        connectionInfos.put(flowControl.getConnectionId(), new ConnectionInfo(flowControl.getConnectionId()));
        totalConnectionsCreated.incrementAndGet();
        log.debug("注册新连接到全局控制器: connectionId={}, 当前活跃连接数={}",
                flowControl.getConnectionId(), activeConnections.size());
    }

    /**
     * 移除连接（连接关闭时）
     */
    public void unregisterConnection(long connectionId) {
        QuicFlowControl removed = activeConnections.remove(connectionId);
        if (removed != null) {
            // 减去该连接的在途字节数
            globalBytesInFlight.addAndGet(-removed.getBytesInFlight());
            totalConnectionsClosed.incrementAndGet();
        }
        connectionInfos.remove(connectionId);
        log.debug("从全局控制器移除连接: connectionId={}, 剩余活跃连接数={}",
                connectionId, activeConnections.size());
    }
    
    /**
     * 连接信息内部类
     */
    private static final class ConnectionInfo {
        private final long connectionId;
        private final long createdTime;
        private volatile long lastActivityTime;
        
        public ConnectionInfo(long connectionId) {
            this.connectionId = connectionId;
            this.createdTime = System.currentTimeMillis();
            this.lastActivityTime = createdTime;
        }
        
        public void updateActivity() {
            this.lastActivityTime = System.currentTimeMillis();
        }
        
        public long getAge() {
            return System.currentTimeMillis() - createdTime;
        }
        
        public long getIdleTime() {
            return System.currentTimeMillis() - lastActivityTime;
        }
    }

    /**
     * 检查全局是否允许发送指定大小的数据
     * @param dataSize 要发送的数据大小
     * @return true=允许发送，false=全局流量超限
     */
    public boolean canSendGlobally(int dataSize) {
        // 检查在途字节数限制
        long currentTotal = globalBytesInFlight.get();
        if (currentTotal + dataSize > GLOBAL_MAX_IN_FLIGHT_BYTES) {
            return false;
        }
        
        // 检查速率限制（每秒15MB）
        checkAndResetSecondWindow();
        long currentSecondBytes = this.currentSecondBytes.get();
        if (currentSecondBytes + dataSize > TARGET_BYTES_PER_SECOND) {
            return false;
        }
        
        // 检查令牌桶限制
        return rateLimiter.canAcquire(dataSize);
    }

    /**
     * 当连接发送数据时，更新全局在途字节数
     */
    public void onGlobalDataSent(int dataSize) {
        // 检查并重置秒窗口
        checkAndResetSecondWindow();
        
        long newTotal = globalBytesInFlight.addAndGet(dataSize);
        totalBytesSent.addAndGet(dataSize);
        currentSecondBytes.addAndGet(dataSize);
        
        // 从速率限制器获取令牌
        rateLimiter.acquireNonBlocking(dataSize);
        
        // 更新峰值
        long currentPeak = peakBytesInFlight.get();
        while (newTotal > currentPeak && !peakBytesInFlight.compareAndSet(currentPeak, newTotal)) {
            currentPeak = peakBytesInFlight.get();
        }
        
        // 更新连接信息
        updateConnectionActivity(dataSize, 0);
        
        log.debug("全局在途字节数增加: +{} bytes, 新总量={} bytes, 总发送={} bytes, 当前秒={} bytes", 
                 dataSize, newTotal, totalBytesSent.get(), currentSecondBytes.get());
    }

    /**
     * 当连接收到ACK时，更新全局在途字节数
     */
    public void onGlobalAckReceived(int ackedSize) {
        long newTotal = globalBytesInFlight.addAndGet(-ackedSize);
        totalBytesReceived.addAndGet(ackedSize);
        
        // 更新连接信息
        updateConnectionActivity(0, ackedSize);
        
        log.debug("全局在途字节数减少: -{} bytes, 新总量={} bytes, 总接收={} bytes", 
                 ackedSize, newTotal, totalBytesReceived.get());
    }

    /**
     * 更新连接活动信息
     */
    private void updateConnectionActivity(long sentBytes, long receivedBytes) {
        // 这里可以更具体地根据连接ID更新特定连接的信息
        // 目前简化处理
    }
    
    /**
     * 启动连接清理任务
     */
    private void startConnectionCleanupTask() {
        Thread cleanupThread = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(30000); // 每30秒清理一次
                    cleanupInactiveConnections();
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "GlobalFlowControl-Cleanup");
        cleanupThread.setDaemon(true);
        cleanupThread.start();
    }
    
    /**
     * 清理非活跃连接
     */
    private void cleanupInactiveConnections() {
        int cleanedCount = 0;
        for (QuicFlowControl flowControl : activeConnections.values()) {
            if (!flowControl.isActive()) {
                unregisterConnection(flowControl.getConnectionId());
                cleanedCount++;
            }
        }
        
        if (cleanedCount > 0) {
            log.info("清理非活跃连接: {} 个", cleanedCount);
        }
    }
    
    /**
     * 获取全局发送速率 (bytes/sec)
     */
    public long getGlobalSendRate() {
        long uptime = System.currentTimeMillis() - startTime;
        return uptime > 0 ? (totalBytesSent.get() * 1000) / uptime : 0;
    }
    
    /**
     * 获取全局接收速率 (bytes/sec)
     */
    public long getGlobalReceiveRate() {
        long uptime = System.currentTimeMillis() - startTime;
        return uptime > 0 ? (totalBytesReceived.get() * 1000) / uptime : 0;
    }
    
    /**
     * 获取运行时间 (秒)
     */
    public long getUptimeSeconds() {
        return (System.currentTimeMillis() - startTime) / 1000;
    }
    
    /**
     * 获取活跃连接数
     */
    public int getActiveConnectionCount() {
        return activeConnections.size();
    }
    
    /**
     * 获取所有活跃连接的流量控制信息
     */
    public java.util.Map<Long, QuicFlowControl> getAllConnections() {
        return new java.util.HashMap<>(activeConnections);
    }
    
    /**
     * 获取全局在途字节数
     */
    public long getGlobalBytesInFlight() {
        return globalBytesInFlight.get();
    }
    
    /**
     * 获取全局最大在途字节数
     */
    public long getGlobalMaxInFlightBytes() {
        return GLOBAL_MAX_IN_FLIGHT_BYTES;
    }
    
    /**
     * 获取全局利用率
     */
    public double getGlobalUtilization() {
        return globalBytesInFlight.get() * 100.0 / GLOBAL_MAX_IN_FLIGHT_BYTES;
    }
    
    /**
     * 获取峰值在途字节数
     */
    public long getPeakBytesInFlight() {
        return peakBytesInFlight.get();
    }
    
    /**
     * 获取总发送字节数
     */
    public long getTotalBytesSent() {
        return totalBytesSent.get();
    }
    
    /**
     * 获取总接收字节数
     */
    public long getTotalBytesReceived() {
        return totalBytesReceived.get();
    }
    
    /**
     * 获取总创建连接数
     */
    public long getTotalConnectionsCreated() {
        return totalConnectionsCreated.get();
    }
    
    /**
     * 获取总关闭连接数
     */
    public long getTotalConnectionsClosed() {
        return totalConnectionsClosed.get();
    }
    
    /**
     * 检查并重置秒窗口
     */
    private void checkAndResetSecondWindow() {
        long now = System.currentTimeMillis();
        if (now - currentSecondStart >= 1000) {
            // 重置到下一秒
            synchronized (this) {
                if (now - currentSecondStart >= 1000) {
                    currentSecondBytes.set(0);
                    currentSecondStart = now;
                    totalSeconds.incrementAndGet();
                }
            }
        }
    }
    
    /**
     * 获取当前秒的流量使用情况
     */
    public long getCurrentSecondBytes() {
        checkAndResetSecondWindow();
        return currentSecondBytes.get();
    }
    
    /**
     * 获取目标每秒流量
     */
    public long getTargetBytesPerSecond() {
        return TARGET_BYTES_PER_SECOND;
    }
    
    /**
     * 获取当前流量利用率（相对于目标）
     */
    public double getCurrentFlowUtilization() {
        checkAndResetSecondWindow();
        long currentBytes = currentSecondBytes.get();
        return (double) currentBytes / TARGET_BYTES_PER_SECOND * 100.0;
    }
    
    /**
     * 获取速率限制器统计
     */
    public String getRateLimiterStats() {
        return String.format("RateLimiter{maxTokens=%d, availableTokens=%d, targetRate=%dMB/s}",
                rateLimiter.getMaxTokens(),
                rateLimiter.getAvailableTokens(),
                TARGET_BYTES_PER_SECOND / (1024 * 1024));
    }

    /**
     * 获取全局流量统计
     */
    public String getGlobalStats() {
        return String.format(
                "GlobalFlowControl{运行时间=%ds, 活跃连接数=%d, 总创建连接=%d, 总关闭连接=%d, " +
                        "全局在途字节数=%d/%d (%.2f%%), 峰值在途=%d, 总发送=%d, 总接收=%d, " +
                        "发送速率=%d/s, 接收速率=%d/s}",
                getUptimeSeconds(), activeConnections.size(), 
                totalConnectionsCreated.get(), totalConnectionsClosed.get(),
                globalBytesInFlight.get(), GLOBAL_MAX_IN_FLIGHT_BYTES,
                globalBytesInFlight.get() * 100.0 / GLOBAL_MAX_IN_FLIGHT_BYTES,
                peakBytesInFlight.get(), totalBytesSent.get(), totalBytesReceived.get(),
                getGlobalSendRate(), getGlobalReceiveRate()
        );
    }
}