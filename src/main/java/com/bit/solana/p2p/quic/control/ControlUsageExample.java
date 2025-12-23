package com.bit.solana.p2p.quic.control;

import lombok.extern.slf4j.Slf4j;

/**
 * 拥塞控制和流量控制使用示例
 */
@Slf4j
public class ControlUsageExample {
    
    public static void main(String[] args) {
        // 1. 基本流量控制器使用
        demonstrateFlowController();
        
        // 2. 拥塞控制算法使用
        demonstrateCongestionControl();
        
        // 3. 集成流控管理器使用
        demonstrateCongestionFlowManager();
        
        // 4. QUIC连接控制集成
        demonstrateQuicConnectionControl();
    }
    
    /**
     * 流量控制器演示
     */
    private static void demonstrateFlowController() {
        log.info("=== 流量控制器演示 ===");
        
        // 创建流量控制器：初始1MB/s，最大5MB/s，突发2MB
        FlowController flowController = new FlowController(
            1024 * 1024,      // 1MB/s
            5 * 1024 * 1024,  // 5MB/s
            2 * 1024 * 1024    // 2MB突发
        );
        
        // 模拟数据发送
        for (int i = 0; i < 10; i++) {
            long dataSize = 100 * 1024; // 100KB数据
            
            boolean canSend = flowController.trySend(dataSize);
            log.info("尝试发送{}KB: {}", dataSize / 1024, canSend ? "成功" : "被限流");
            
            if (!canSend) {
                // 等待令牌补充
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
        
        // 输出统计信息
        FlowControlStats stats = flowController.getStats();
        log.info("流量控制统计: {}", stats.formatStats());
        
        flowController.shutdown();
    }
    
    /**
     * 拥塞控制算法演示
     */
    private static void demonstrateCongestionControl() {
        log.info("=== 拥塞控制算法演示 ===");
        
        // 创建CUBIC拥塞控制
        CubicCongestionControl cubicControl = new CubicCongestionControl();
        
        // 模拟网络行为
        simulateNetworkBehavior(cubicControl);
        
        // 输出拥塞状态
        CongestionState state = cubicControl.getState();
        log.info("拥塞控制状态:");
        log.info("  拥塞窗口: {}KB", state.getCongestionWindow() / 1024);
        log.info("  当前速率: {}KB/s", state.getCurrentSendRate() / 1024);
        log.info("  平滑RTT: {}ms", state.getSmoothedRtt());
        log.info("  丢包率: {}%", state.getPacketLossRate() * 100);
        log.info("  平均吞吐量: {}KB/s", state.getAverageThroughput() / 1024);
    }
    
    /**
     * 集成流控管理器演示
     */
    private static void demonstrateCongestionFlowManager() {
        log.info("=== 集成流控管理器演示 ===");
        
        CongestionFlowManager flowManager = new CongestionFlowManager();
        
        // 设置最大速率
        flowManager.setMaxSendRate(3 * 1024 * 1024); // 3MB/s
        
        // 模拟数据发送
        for (int i = 0; i < 20; i++) {
            long dataSize = 50 * 1024; // 50KB数据
            
            boolean canSend = flowManager.canSend(dataSize);
            if (canSend) {
                flowManager.recordPacketSent(dataSize);
                log.debug("发送数据: {}KB", dataSize / 1024);
            } else {
                log.debug("数据被限流: {}KB", dataSize / 1024);
            }
            
            // 模拟ACK和丢包
            if (i % 5 == 0) {
                flowManager.onAck(dataSize, 50 + (int)(Math.random() * 100)); // 50-150ms RTT
            } else if (i % 15 == 0) {
                flowManager.onPacketLoss(dataSize); // 模拟丢包
            }
            
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                break;
            }
        }
        
        // 输出详细报告
        String report = flowManager.getComprehensiveReport();
        log.info("综合报告:\n{}", report);
        
        flowManager.shutdown();
    }
    
    /**
     * QUIC连接控制集成演示
     */
    private static void demonstrateQuicConnectionControl() {
        log.info("=== QUIC连接控制集成演示 ===");
        
        // 模拟连接ID
        long connectionId = 12345L;
        
        // 创建连接控制
        QuicConnectionControl connectionControl = new QuicConnectionControl(connectionId);
        
        // 模拟数据发送流程 - 先确保有数据包真正发送
        for (int i = 0; i < 5; i++) {
            long dataSize = 10 * 1024; // 10KB数据（更小的数据包）
            
            // 等待更长时间确保令牌桶补充
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                break;
            }
            
            // 检查是否可以发送
            if (connectionControl.canSend(dataSize)) {
                connectionControl.onPacketSent(dataSize);
                log.info("发送数据: {}KB", dataSize / 1024);
                
                // 模拟ACK（90%概率）
                if (Math.random() < 0.9) {
                    try {
                        Thread.sleep(50 + (long)(Math.random() * 50)); // 50-100ms RTT
                    } catch (InterruptedException e) {
                        break;
                    }
                    connectionControl.onAckReceived(dataSize, dataSize);
                    log.info("收到ACK");
                } else {
                    // 模拟丢包
                    connectionControl.onPacketLoss(dataSize);
                    log.warn("数据丢失");
                }
            } else {
                log.info("数据被限流");
            }
        }
        
        // 再模拟一些被限流的情况来展示对比
        log.info("--- 开始模拟限流情况 ---");
        for (int i = 0; i < 5; i++) {
            long dataSize = 100 * 1024; // 100KB数据（较大的数据包，容易被限流）
            
            if (connectionControl.canSend(dataSize)) {
                connectionControl.onPacketSent(dataSize);
                log.info("发送数据: {}KB", dataSize / 1024);
                connectionControl.onAckReceived(dataSize, dataSize);
            } else {
                log.info("数据被限流");
            }
            
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                break;
            }
        }
        
        // 为了演示统计功能，手动添加一些数据包统计
        log.info("手动添加统计数据来演示功能...");
        for (int i = 0; i < 3; i++) {
            connectionControl.onPacketSent(10 * 1024); // 模拟发送
            connectionControl.onAckReceived(10 * 1024, 10 * 1024); // 模拟ACK
        }
        for (int i = 0; i < 1; i++) {
            connectionControl.onPacketSent(10 * 1024); // 模拟发送
            connectionControl.onPacketLoss(10 * 1024); // 模拟丢失
        }
        
        // 输出连接状态
        log.info("连接状态: {}", connectionControl.getConnectionControlStatus());
        log.info("详细报告:\n{}", connectionControl.getDetailedReport());
        
        connectionControl.shutdown();
    }
    
    /**
     * 模拟网络行为
     */
    private static void simulateNetworkBehavior(CubicCongestionControl control) {
        for (int i = 0; i < 100; i++) {
            long dataSize = 1024 + (long)(Math.random() * 4096); // 1KB-5KB随机数据
            
            // 模拟ACK确认（90%概率）
            if (Math.random() < 0.9) {
                long rtt = 20 + (long)(Math.random() * 180); // 20-200ms RTT
                control.onAck(dataSize, rtt);
            } else {
                // 模拟丢包（10%概率）
                control.onPacketLoss(dataSize);
            }
            
            try {
                Thread.sleep(10); // 10ms间隔
            } catch (InterruptedException e) {
                break;
            }
        }
    }
}