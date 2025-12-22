package com.bit.solana.p2p.quic.control;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 全局流量控制器
 * 管理所有连接的总发送流量，防止全局发送溢出
 */
@Slf4j
public class GlobalFlowControl {
    // 全局最大总在途字节数（可根据系统性能调整，例如20MB）
    private final long GLOBAL_MAX_IN_FLIGHT_BYTES = 15 * 1024 * 1024;
    // 全局总在途字节数（所有连接的在途字节总和）
    private final AtomicLong globalBytesInFlight = new AtomicLong(0);
    // 活跃连接集合（连接ID -> 流量控制器）
    private final ConcurrentMap<Long, QuicFlowControl> activeConnections = new ConcurrentHashMap<>();

    // 单例实例
    private static final GlobalFlowControl INSTANCE = new GlobalFlowControl();

    private GlobalFlowControl() {}

    public static GlobalFlowControl getInstance() {
        return INSTANCE;
    }

    /**
     * 注册新连接
     */
    public void registerConnection(QuicFlowControl flowControl) {
        activeConnections.put(flowControl.getConnectionId(), flowControl);
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
            globalBytesInFlight.addAndGet(-removed.getBytesInFlight().get());
            log.debug("从全局控制器移除连接: connectionId={}, 剩余活跃连接数={}",
                    connectionId, activeConnections.size());
        }
    }

    /**
     * 检查全局是否允许发送指定大小的数据
     * @param dataSize 要发送的数据大小
     * @return true=允许发送，false=全局流量超限
     */
    public boolean canSendGlobally(int dataSize) {
        long currentTotal = globalBytesInFlight.get();
        return currentTotal + dataSize <= GLOBAL_MAX_IN_FLIGHT_BYTES;
    }

    /**
     * 当连接发送数据时，更新全局在途字节数
     */
    public void onGlobalDataSent(int dataSize) {
        long newTotal = globalBytesInFlight.addAndGet(dataSize);
        log.debug("全局在途字节数增加: +{} bytes, 新总量={} bytes", dataSize, newTotal);
    }

    /**
     * 当连接收到ACK时，更新全局在途字节数
     */
    public void onGlobalAckReceived(int ackedSize) {
        long newTotal = globalBytesInFlight.addAndGet(-ackedSize);
        log.debug("全局在途字节数减少: -{} bytes, 新总量={} bytes", ackedSize, newTotal);
    }

    /**
     * 获取全局流量统计
     */
    public String getGlobalStats() {
        return String.format(
                "GlobalFlowControl{活跃连接数=%d, 全局在途字节数=%d/%d, 全局利用率=%.2f%%}",
                activeConnections.size(),
                globalBytesInFlight.get(),
                GLOBAL_MAX_IN_FLIGHT_BYTES,
                globalBytesInFlight.get() * 100.0 / GLOBAL_MAX_IN_FLIGHT_BYTES
        );
    }
}