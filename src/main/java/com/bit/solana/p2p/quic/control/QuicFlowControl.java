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
    
    // 新增：连接状态监控
    private volatile long lastActivityTime = System.currentTimeMillis();
    private final AtomicLong totalBytesSent = new AtomicLong(0);
    private final AtomicLong totalBytesReceived = new AtomicLong(0);
    private final AtomicInteger sendBurstCount = new AtomicInteger(0);
    private final AtomicInteger receiveBurstCount = new AtomicInteger(0);
    
    // 新增：MTU探测集成
    private final MtuDiscovery mtuDiscovery;
    private volatile int currentMtu = 1400; // 默认MTU
    private final AtomicInteger mtuOptimizedCount = new AtomicInteger(0);
    
    // 新增：自适应窗口调整参数
    private static final int BURST_THRESHOLD = 10; // 突发阈值
    private static final long ACTIVITY_TIMEOUT = 30000; // 活动超时时间 (30秒)
    private static final double WINDOW_GROWTH_FACTOR = 1.1; // 窗口增长因子
    private static final double WINDOW_SHRINK_FACTOR = 0.8; // 窗口缩小因子
    private static final int MTU_OPTIMIZATION_THRESHOLD = 100; // MTU优化触发阈值

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
        this.lastActivityTime = System.currentTimeMillis();
        
        // 初始化MTU探测
        this.mtuDiscovery = new MtuDiscovery(connectionId);
        
        log.debug("流量控制器初始化: connectionId={}, sendWindowSize={}, receiveWindowSize={}, mtu={}",
                connectionId, sendWindowSize.get(), receiveWindowSize.get(), currentMtu);
    }
    
    /**
     * 启动MTU发现
     */
    public void startMtuDiscovery() {
        mtuDiscovery.startDiscovery();
        log.info("启动MTU发现: connectionId={}", connectionId);
    }
    
    /**
     * 获取当前MTU
     */
    public int getCurrentMtu() {
        return currentMtu;
    }
    
    /**
     * 设置MTU（当MTU发现完成时调用）
     */
    public void setMtu(int mtu) {
        this.currentMtu = mtu;
        mtuOptimizedCount.incrementAndGet();
        log.info("设置MTU: connectionId={}, mtu={}, optimizeCount={}", 
                connectionId, mtu, mtuOptimizedCount.get());
        
        // 根据MTU调整发送窗口
        adjustWindowForMtu(mtu);
    }
    
    /**
     * 根据MTU调整窗口大小
     */
    private void adjustWindowForMtu(int mtu) {
        // 计算基于MTU的最佳窗口大小
        int optimalWindowSize = (mtu / 8) * 100; // 基于数据包数量的窗口调整
        optimalWindowSize = Math.max(MIN_WINDOW_SIZE, 
            Math.min(optimalWindowSize, maxSendWindow.get()));
        
        sendWindowSize.set(optimalWindowSize);
        sendWindow.set(optimalWindowSize);
        
        log.debug("基于MTU调整窗口: connectionId={}, mtu={}, newWindowSize={}", 
                 connectionId, mtu, optimalWindowSize);
    }
    
    /**
     * 检查是否可以发送指定大小的数据
     * @param dataSize 要发送的数据大小
     * @return true=可以发送，false=受流控限制
     */
    public boolean canSend(int dataSize) {
        // 更新活动时间
        updateActivityTime();
        
        // 检查连接级发送窗口
        int availableWindow = sendWindow.get() - (int) bytesInFlight.get();
        if (availableWindow < dataSize) {
            return false;
        }
        
        // 检查全局流量限制
        return globalFlowControl.canSendGlobally(dataSize);
    }
    
    /**
     * 发送数据时更新在途字节数
     * @param dataSize 发送的数据大小
     */
    public void onDataSent(int dataSize) {
        bytesInFlight.addAndGet(dataSize);
        totalBytesSent.addAndGet(dataSize);
        // 同步更新全局在途字节数
        globalFlowControl.onGlobalDataSent(dataSize);
        
        // 检测突发流量
        detectSendBurst();
        
        // 自适应窗口调整
        adaptWindowSize();
        
        log.debug("发送数据: connectionId={}, dataSize={}, bytesInFlight={}, totalSent={}",
                connectionId, dataSize, bytesInFlight.get(), totalBytesSent.get());
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
        totalBytesReceived.addAndGet(dataSize);
        
        // 更新活动时间
        updateActivityTime();
        
        // 检测接收突发
        detectReceiveBurst();
        
        log.debug("接收数据: connectionId={}, dataSize={}, receiveWindow={}, totalReceived={}", 
                 connectionId, dataSize, receiveWindow.get(), totalBytesReceived.get());
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
            "receiveWindow=%d/%d (%d%%), bytesInFlight=%d, bytesReceived=%d, " +
            "totalSent=%d, totalReceived=%d, sendRate=%d, receiveRate=%d, active=%s, " +
            "currentMtu=%d, mtuState=%s, mtuSuccessRate=%.2f%%}",
            connectionId,
            sendWindow.get(), sendWindowSize.get(), getSendWindowUtilization(),
            receiveWindow.get(), receiveWindowSize.get(), getReceiveWindowUtilization(),
            bytesInFlight.get(), bytesReceived.get(),
            totalBytesSent.get(), totalBytesReceived.get(),
            getSendRate(), getReceiveRate(), isActive(),
            currentMtu, mtuDiscovery.getState(), mtuDiscovery.getSuccessRate() * 100
        );
    }

    /**
     * 更新活动时间
     */
    private void updateActivityTime() {
        lastActivityTime = System.currentTimeMillis();
    }
    
    /**
     * 检测发送突发流量
     */
    private void detectSendBurst() {
        sendBurstCount.incrementAndGet();
        // 如果突发次数超过阈值，临时减小窗口
        if (sendBurstCount.get() > BURST_THRESHOLD) {
            int currentSize = sendWindowSize.get();
            int newSize = Math.max(MIN_WINDOW_SIZE, (int) (currentSize * WINDOW_SHRINK_FACTOR));
            sendWindowSize.set(newSize);
            sendWindow.set(newSize);
            sendBurstCount.set(0); // 重置计数
            log.warn("检测到发送突发，临时减小发送窗口: connectionId={}, newSize={}", 
                     connectionId, newSize);
        }
    }
    
    /**
     * 检测接收突发流量
     */
    private void detectReceiveBurst() {
        receiveBurstCount.incrementAndGet();
        if (receiveBurstCount.get() > BURST_THRESHOLD) {
            log.warn("检测到接收突发流量: connectionId={}, burstCount={}", 
                     connectionId, receiveBurstCount.get());
            receiveBurstCount.set(0);
        }
    }
    
    /**
     * 自适应窗口调整
     */
    private void adaptWindowSize() {
        long currentTime = System.currentTimeMillis();
        long timeSinceLastActivity = currentTime - lastActivityTime;
        
        // 检查是否需要MTU优化
        checkMtuOptimization();
        
        // 如果连接长时间不活跃，减小窗口节省资源
        if (timeSinceLastActivity > ACTIVITY_TIMEOUT) {
            int currentSendSize = sendWindowSize.get();
            int newSendSize = Math.max(MIN_WINDOW_SIZE, (int) (currentSendSize * WINDOW_SHRINK_FACTOR));
            sendWindowSize.set(newSendSize);
            sendWindow.set(newSendSize);
            
            int currentReceiveSize = receiveWindowSize.get();
            int newReceiveSize = Math.max(MIN_WINDOW_SIZE, (int) (currentReceiveSize * WINDOW_SHRINK_FACTOR));
            receiveWindowSize.set(newReceiveSize);
            receiveWindow.set(newReceiveSize);
            
            log.info("连接长时间不活跃，减小窗口: connectionId={}, sendWindow={}, receiveWindow={}", 
                     connectionId, newSendSize, newReceiveSize);
        }
    }
    
    /**
     * 检查MTU优化
     */
    private void checkMtuOptimization() {
        // 每传输一定数据量后检查MTU优化
        if (mtuOptimizedCount.get() < MTU_OPTIMIZATION_THRESHOLD && 
            mtuDiscovery.isDiscoveryComplete() && 
            mtuDiscovery.getState() != MtuDiscovery.MtuState.OPTIMIZED) {
            
            int recommendedMtu = mtuDiscovery.getRecommendedMtu();
            if (recommendedMtu != currentMtu && recommendedMtu > currentMtu) {
                setMtu(recommendedMtu);
                mtuOptimizedCount.addAndGet(MTU_OPTIMIZATION_THRESHOLD);
            }
        }
    }
    
    /**
     * 获取连接状态
     */
    public boolean isActive() {
        return (System.currentTimeMillis() - lastActivityTime) < ACTIVITY_TIMEOUT;
    }
    
    /**
     * 获取连接空闲时间
     */
    public long getIdleTime() {
        return System.currentTimeMillis() - lastActivityTime;
    }
    
    /**
     * 获取发送速率 (bytes/sec)
     */
    public long getSendRate() {
        long uptime = System.currentTimeMillis() - lastActivityTime;
        return uptime > 0 ? (totalBytesSent.get() * 1000) / uptime : 0;
    }
    
    /**
     * 获取接收速率 (bytes/sec)
     */
    public long getReceiveRate() {
        long uptime = System.currentTimeMillis() - lastActivityTime;
        return uptime > 0 ? (totalBytesReceived.get() * 1000) / uptime : 0;
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
     * 获取发送窗口
     */
    public int getSendWindow() {
        return sendWindow.get();
    }
    
    /**
     * 获取发送窗口大小
     */
    public int getSendWindowSize() {
        return sendWindowSize.get();
    }
    
    /**
     * 获取接收窗口
     */
    public int getReceiveWindow() {
        return receiveWindow.get();
    }
    
    /**
     * 获取接收窗口大小
     */
    public int getReceiveWindowSize() {
        return receiveWindowSize.get();
    }
    
    /**
     * 获取在途字节数
     */
    public long getBytesInFlight() {
        return bytesInFlight.get();
    }
    
    /**
     * 获取已接收字节数
     */
    public long getBytesReceived() {
        return bytesReceived.get();
    }

    // 新增：连接关闭时调用，从全局控制器移除
    public void close() {
        globalFlowControl.unregisterConnection(connectionId);
        log.info("流量控制器关闭: connectionId={}, totalSent={}, totalReceived={}", 
                connectionId, totalBytesSent.get(), totalBytesReceived.get());
    }
}