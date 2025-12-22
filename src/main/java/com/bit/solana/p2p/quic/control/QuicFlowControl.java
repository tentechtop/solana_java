package com.bit.solana.p2p.quic.control;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * QUIC流量控制器
 * 实现连接级别的流控制和传输窗口管理
 */
@Slf4j
@Data
public class QuicFlowControl {
    // 连接ID
    private final long connectionId;

    // 新增：全局流量控制器引用
    private final GlobalFlowControl globalFlowControl;

    
    // 发送窗口相关
    private final AtomicInteger sendWindow = new AtomicInteger(0);           // 当前发送窗口大小
    private final AtomicInteger sendWindowSize = new AtomicInteger(64 * 1024); // 发送窗口大小 (64KB初始值)
    private final AtomicLong bytesInFlight = new AtomicLong(0);              // 在途字节数
    private final AtomicInteger maxSendWindow = new AtomicInteger(1024 * 1024); // 最大发送窗口 (1MB)
    
    // 接收窗口相关
    private final AtomicInteger receiveWindow = new AtomicInteger(0);          // 当前接收窗口大小
    private final AtomicInteger receiveWindowSize = new AtomicInteger(64 * 1024); // 接收窗口大小 (64KB初始值)
    private final AtomicInteger maxReceiveWindow = new AtomicInteger(1024 * 1024); // 最大接收窗口 (1MB)
    private final AtomicLong bytesReceived = new AtomicLong(0);              // 已接收字节数
    
    // 流控制阈值
    private static final int MIN_WINDOW_SIZE = 16 * 1024;  // 最小窗口 16KB
    private static final int WINDOW_INCREASE_STEP = 8 * 1024; // 窗口增长步长 8KB
    private static final double WINDOW_DECREASE_FACTOR = 0.5; // 窗口缩小因子
    
    /**
     * 构造函数
     */
    public QuicFlowControl(long connectionId) {
        this.connectionId = connectionId;
        this.sendWindow.set(sendWindowSize.get());
        this.receiveWindow.set(receiveWindowSize.get());
        // 注册到全局控制器
        this.globalFlowControl = GlobalFlowControl.getInstance();
        this.globalFlowControl.registerConnection(this);
        log.debug("流量控制器初始化: connectionId={}, sendWindowSize={}, receiveWindowSize={}",
                connectionId, sendWindowSize.get(), receiveWindowSize.get());
    }
    
    /**
     * 检查是否可以发送指定大小的数据
     * @param dataSize 要发送的数据大小
     * @return true=可以发送，false=受流控限制
     */
    public boolean canSend(int dataSize) {
        int availableWindow = sendWindow.get() - (int) bytesInFlight.get();
        return availableWindow >= dataSize;
    }
    
    /**
     * 发送数据时更新在途字节数
     * @param dataSize 发送的数据大小
     */
    public void onDataSent(int dataSize) {
        bytesInFlight.addAndGet(dataSize);
        // 同步更新全局在途字节数
        globalFlowControl.onGlobalDataSent(dataSize);
        log.debug("发送数据: connectionId={}, dataSize={}, bytesInFlight={}",
                connectionId, dataSize, bytesInFlight.get());
    }
    
    /**
     * 收到ACK时更新发送窗口和释放在途字节
     * @param ackedSize 确认的数据大小
     */
    public void onAckReceived(int ackedSize) {
        // 释放在途字节
        bytesInFlight.addAndGet(-ackedSize);
        // 同步更新全局在途字节数
        globalFlowControl.onGlobalAckReceived(ackedSize);
        // 调整发送窗口
        adjustSendWindow();
        log.debug("收到ACK: connectionId={}, ackedSize={}, bytesInFlight={}, sendWindow={}",
                connectionId, ackedSize, bytesInFlight.get(), sendWindow.get());
    }

    
    /**
     * 发生丢包时减小发送窗口
     */
    public void onPacketLoss() {
        // 减小发送窗口
        int newWindowSize = Math.max(
            MIN_WINDOW_SIZE,
            (int) (sendWindowSize.get() * WINDOW_DECREASE_FACTOR)
        );
        sendWindowSize.set(newWindowSize);
        sendWindow.set(newWindowSize);
        
        log.warn("发生丢包，减小发送窗口: connectionId={}, newWindowSize={}", connectionId, newWindowSize);
    }
    
    /**
     * 检查是否可以接收指定大小的数据
     * @param dataSize 要接收的数据大小
     * @return true=可以接收，false=接收窗口不足
     */
    public boolean canReceive(int dataSize) {
        int availableWindow = receiveWindow.get();
        return availableWindow >= dataSize;
    }
    
    /**
     * 接收数据时更新窗口
     * @param dataSize 接收的数据大小
     */
    public void onDataReceived(int dataSize) {
        receiveWindow.addAndGet(-dataSize);
        bytesReceived.addAndGet(dataSize);
        log.debug("接收数据: connectionId={}, dataSize={}, receiveWindow={}", 
                 connectionId, dataSize, receiveWindow.get());
    }
    
    /**
     * 应用已处理数据并恢复接收窗口
     * @param processedSize 已处理的数据大小
     */
    public void onDataProcessed(int processedSize) {
        receiveWindow.addAndGet(processedSize);
        // 确保不超过最大接收窗口
        int maxAllowed = maxReceiveWindow.get();
        if (receiveWindow.get() > maxAllowed) {
            receiveWindow.set(maxAllowed);
        }
        
        log.debug("数据处理完成: connectionId={}, processedSize={}, receiveWindow={}", 
                 connectionId, processedSize, receiveWindow.get());
    }
    
    /**
     * 动态调整发送窗口
     */
    private void adjustSendWindow() {
        // 如果在途字节数较少，可以增加窗口
        long currentInFlight = bytesInFlight.get();
        int currentWindowSize = sendWindowSize.get();
        
        if (currentInFlight < currentWindowSize * 0.5) {
            // 网络空闲，可以增加窗口
            int newWindowSize = Math.min(
                maxSendWindow.get(),
                currentWindowSize + WINDOW_INCREASE_STEP
            );
            if (newWindowSize != currentWindowSize) {
                sendWindowSize.set(newWindowSize);
                sendWindow.set(newWindowSize);
                log.debug("增加发送窗口: connectionId={}, oldSize={}, newSize={}", 
                         connectionId, currentWindowSize, newWindowSize);
            }
        }
    }
    
    /**
     * 获取发送窗口利用率
     * @return 利用率百分比 (0-100)
     */
    public int getSendWindowUtilization() {
        long inFlight = bytesInFlight.get();
        int windowSize = sendWindowSize.get();
        return windowSize > 0 ? (int) ((inFlight * 100) / windowSize) : 0;
    }
    
    /**
     * 获取接收窗口利用率
     * @return 利用率百分比 (0-100)
     */
    public int getReceiveWindowUtilization() {
        int used = receiveWindowSize.get() - receiveWindow.get();
        int windowSize = receiveWindowSize.get();
        return windowSize > 0 ? (used * 100) / windowSize : 0;
    }
    
    /**
     * 重置流量控制器
     */
    public void reset() {
        // 减去当前连接的在途字节数
        globalFlowControl.onGlobalAckReceived((int) bytesInFlight.get());
        bytesInFlight.set(0);
        bytesReceived.set(0);
        sendWindow.set(sendWindowSize.get());
        receiveWindow.set(receiveWindowSize.get());
        log.info("流量控制器重置: connectionId={}", connectionId);
    }
    
    /**
     * 获取流控统计信息
     */
    public String getStats() {
        return String.format(
            "QuicFlowControl{connectionId=%d, sendWindow=%d/%d (%d%%), " +
            "receiveWindow=%d/%d (%d%%), bytesInFlight=%d, bytesReceived=%d}",
            connectionId,
            sendWindow.get(), sendWindowSize.get(), getSendWindowUtilization(),
            receiveWindow.get(), receiveWindowSize.get(), getReceiveWindowUtilization(),
            bytesInFlight.get(), bytesReceived.get()
        );
    }

    // 新增：连接关闭时调用，从全局控制器移除
    public void close() {
        globalFlowControl.unregisterConnection(connectionId);
    }
}