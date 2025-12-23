package com.bit.solana.p2p.quic.control;

import com.bit.solana.p2p.quic.QuicConnection;
import lombok.extern.slf4j.Slf4j;

/**
 * QUIC连接的拥塞控制和流量管理集成
 * 为QuicConnection提供智能的传输控制
 */
@Slf4j
public class QuicConnectionControl {
    
    private final long connectionId;
    private final CongestionFlowManager flowManager;
    
    // 时间跟踪
    private volatile long lastAckTime;
    private volatile long packetSendTime;
    
    public QuicConnectionControl(long connectionId) {
        this.connectionId = connectionId;
        this.flowManager = new CongestionFlowManager();
        this.lastAckTime = System.currentTimeMillis();
        
        // 启动流控管理器
        flowManager.start();
        
        log.info("[连接控制启动] 连接ID:{}", connectionId);
    }
    
    /**
     * 检查是否可以发送数据
     * @param dataSize 数据大小
     * @return 是否可以发送
     */
    public boolean canSend(long dataSize) {
        return flowManager.canSend(dataSize);
    }
    
    /**
     * 获取发送许可（阻塞式）
     * @param dataSize 数据大小
     * @param timeoutMs 超时时间
     * @return 是否获得许可
     */
    public boolean acquireSendPermission(long dataSize, long timeoutMs) {
        return flowManager.acquireSendPermission(dataSize, timeoutMs);
    }
    
    /**
     * 记录数据包发送
     * @param packetSize 数据包大小
     */
    public void onPacketSent(long packetSize) {
        packetSendTime = System.currentTimeMillis();
        flowManager.recordPacketSent(packetSize);
    }
    
    /**
     * 处理ACK确认
     * @param ackedBytes 确认字节数
     * @param packetSize 原始数据包大小
     */
    public void onAckReceived(long ackedBytes, long packetSize) {
        long currentTime = System.currentTimeMillis();
        long rtt = currentTime - packetSendTime;
        
        // 更新最后ACK时间
        lastAckTime = currentTime;
        
        // 通知流控管理器
        flowManager.onAck(ackedBytes, Math.max(1, rtt));
        
        log.debug("[连接ACK] 连接ID:{}, 确认:{}B, RTT:{}ms", 
                connectionId, ackedBytes, rtt);
    }
    
    /**
     * 处理数据包丢失
     * @param lostBytes 丢失字节数
     */
    public void onPacketLoss(long lostBytes) {
        flowManager.onPacketLoss(lostBytes);
        
        log.warn("[连接丢包] 连接ID:{}, 丢失:{}B", connectionId, lostBytes);
    }
    
    /**
     * 检测超时（长时间未收到ACK）
     */
    public boolean checkTimeout(long timeoutMs) {
        long timeSinceLastAck = System.currentTimeMillis() - lastAckTime;
        return timeSinceLastAck > timeoutMs;
    }
    
    /**
     * 设置最大发送速率
     * @param maxRate 最大速率（字节/秒）
     */
    public void setMaxSendRate(long maxRate) {
        flowManager.setMaxSendRate(maxRate);
    }
    
    /**
     * 获取当前拥塞窗口
     */
    public long getCongestionWindow() {
        return flowManager.getCongestionWindow();
    }
    
    /**
     * 获取当前发送速率
     */
    public long getCurrentSendRate() {
        return flowManager.getCurrentSendRate();
    }
    
    /**
     * 获取连接控制状态
     */
    public String getConnectionControlStatus() {
        return String.format(
            "连接ID:%d | 拥塞窗口:%s | 发送速率:%s | 最后ACK:%dms前",
            connectionId,
            formatBytes(getCongestionWindow()),
            formatBytes(getCurrentSendRate()),
            System.currentTimeMillis() - lastAckTime
        );
    }
    
    /**
     * 获取详细报告
     */
    public String getDetailedReport() {
        return flowManager.getComprehensiveReport();
    }
    
    /**
     * 重置连接控制状态
     */
    public void reset() {
        flowManager.reset();
        lastAckTime = System.currentTimeMillis();
        packetSendTime = System.currentTimeMillis();
        
        log.info("[连接控制重置] 连接ID:{}", connectionId);
    }
    
    /**
     * 关闭连接控制
     */
    public void shutdown() {
        flowManager.shutdown();
        log.info("[连接控制关闭] 连接ID:{}", connectionId);
    }
    
    /**
     * 创建连接控制实例的工厂方法
     */
    public static QuicConnectionControl createForConnection(QuicConnection connection) {
        long connectionId = connection.getConnectionId();
        
        QuicConnectionControl control = new QuicConnectionControl(connectionId);
        
        // 根据连接类型设置合适的参数
        if (connection.isUDP()) {
            // UDP连接：较高的初始速率，较大的突发
            control.setMaxSendRate(5 * 1024 * 1024); // 5MB/s
        } else {
            // TCP连接：保守的参数
            control.setMaxSendRate(2 * 1024 * 1024); // 2MB/s
        }
        
        return control;
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
}