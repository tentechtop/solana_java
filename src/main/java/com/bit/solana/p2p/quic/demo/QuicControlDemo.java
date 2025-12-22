package com.bit.solana.p2p.quic.demo;

import com.bit.solana.p2p.quic.control.*;
import lombok.extern.slf4j.Slf4j;

import java.util.Random;
import java.util.concurrent.*;

/**
 * QUIC流量控制和拥塞控制演示
 * 展示MTU探测、流量控制（每秒15M）和拥塞控制的使用
 */
@Slf4j
public class QuicControlDemo {
    
    // 演示配置
    private static final int NUM_CONNECTIONS = 5;
    private static final long DEMO_DURATION_SECONDS = 30;
    private static final long TARGET_THROUGHPUT_MBPS = 15; // 每秒15MB目标
    
    public static void main(String[] args) {
        System.out.println("=".repeat(80));
        System.out.println("🚀 QUIC流量控制和拥塞控制演示系统");
        System.out.println("=".repeat(80));
        System.out.println("演示目标:");
        System.out.println("  • MTU探测和优化");
        System.out.println("  • 全局流量控制（每秒15MB限制）");
        System.out.println("  • 多场景拥塞控制配置");
        System.out.println("  • 实时性能监控");
        System.out.println("=".repeat(80));
        
        try {
            // 演示1: 全局流量控制
            demonstrateGlobalFlowControl();
            
            // 演示2: MTU探测
            demonstrateMtuDiscovery();
            
            // 演示3: 拥塞控制配置
            demonstrateCongestionControlConfig();
            
            // 演示4: 综合场景模拟
            demonstrateComprehensiveScenario();
            
        } catch (Exception e) {
            log.error("演示过程中发生错误", e);
        }
        
        System.out.println("=".repeat(80));
        System.out.println("✅ 演示完成！");
        System.out.println("=".repeat(80));
    }
    
    /**
     * 演示全局流量控制
     */
    private static void demonstrateGlobalFlowControl() {
        System.out.println("\n📊 演示1: 全局流量控制（每秒15MB限制）");
        System.out.println("-".repeat(60));
        
        GlobalFlowControl globalControl = GlobalFlowControl.getInstance();
        
        System.out.println("📋 全局流量控制配置:");
        System.out.println("  • 最大在途字节数: " + formatBytes(globalControl.getGlobalMaxInFlightBytes()));
        System.out.println("  • 目标每秒流量: " + formatBytes(globalControl.getTargetBytesPerSecond()) + "/s");
        System.out.println("  • 初始状态: " + globalControl.getGlobalStats());
        
        // 模拟多个连接同时发送数据
        ExecutorService executor = Executors.newFixedThreadPool(NUM_CONNECTIONS);
        CountDownLatch latch = new CountDownLatch(NUM_CONNECTIONS);
        
        for (int i = 0; i < NUM_CONNECTIONS; i++) {
            final int connectionId = i + 1;
            executor.submit(() -> {
                try {
                    simulateDataTransfer(globalControl, connectionId);
                } finally {
                    latch.countDown();
                }
            });
        }
        
        // 监控全局状态
        ScheduledExecutorService monitor = Executors.newScheduledThreadPool(1);
        monitor.scheduleAtFixedRate(() -> {
            System.out.printf("  📈 全局状态: 利用率=%.1f%%, 当前秒流量=%s, 活跃连接=%d%n",
                    globalControl.getCurrentFlowUtilization(),
                    formatBytes(globalControl.getCurrentSecondBytes()),
                    globalControl.getActiveConnectionCount());
        }, 1, 1, TimeUnit.SECONDS);
        
        try {
            latch.await();
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        monitor.shutdown();
        executor.shutdown();
        
        System.out.println("  📊 最终统计: " + globalControl.getGlobalStats());
        System.out.println("  🎯 流量控制状态: " + globalControl.getRateLimiterStats());
        System.out.println("✅ 全局流量控制演示完成\n");
    }
    
    /**
     * 演示MTU探测
     */
    private static void demonstrateMtuDiscovery() {
        System.out.println("🔍 演示2: MTU探测和优化");
        System.out.println("-".repeat(60));
        
        long connectionId = System.currentTimeMillis();
        MtuDiscovery mtuDiscovery = new MtuDiscovery(connectionId);
        
        System.out.println("📋 MTU探测配置:");
        System.out.println("  • 最小MTU: 1200 bytes");
        System.out.println("  • 最大MTU: 1500 bytes");
        System.out.println("  • 初始MTU: 1400 bytes");
        System.out.println("  • 探测步长: 50 bytes");
        
        // 启动MTU发现
        mtuDiscovery.startDiscovery();
        
        // 模拟探测过程
        int probeCount = 0;
        while (!mtuDiscovery.isDiscoveryComplete() && probeCount < 20) {
            try {
                Thread.sleep(200);
                probeCount++;
                
                if (probeCount % 3 == 0) {
                    System.out.printf("  🔍 探测进度: %s, 尝试次数=%d, 成功率=%.1f%%%n",
                            mtuDiscovery.getState(),
                            mtuDiscovery.getProbeHistory().size(),
                            mtuDiscovery.getSuccessRate() * 100);
                }
            } catch (InterruptedException e) {
                break;
            }
        }
        
        System.out.println("  📊 探测结果: " + mtuDiscovery.getStats());
        
        // 显示探测历史
        System.out.println("  📜 探测历史:");
        mtuDiscovery.getProbeHistory().stream()
            .limit(5)
            .forEach(result -> {
                System.out.printf("    • MTU=%4d bytes: %s (响应时间: %dms)%n",
                        result.getMtuSize(),
                        result.isSuccessful() ? "✅ 成功" : "❌ 失败",
                        result.getResponseTime());
            });
        
        System.out.println("✅ MTU探测演示完成\n");
    }
    
    /**
     * 演示拥塞控制配置
     */
    private static void demonstrateCongestionControlConfig() {
        System.out.println("⚙️ 演示3: 多场景拥塞控制配置");
        System.out.println("-".repeat(60));
        
        CongestionControlConfig.CongestionScenario[] scenarios = {
            CongestionControlConfig.CongestionScenario.HIGH_SPEED_LAN,
            CongestionControlConfig.CongestionScenario.BROADBAND,
            CongestionControlConfig.CongestionScenario.MOBILE,
            CongestionControlConfig.CongestionScenario.SATELLITE,
            CongestionControlConfig.CongestionScenario.DATA_CENTER,
            CongestionControlConfig.CongestionScenario.WIRELESS,
            CongestionControlConfig.CongestionScenario.CONSTRAINED
        };
        
        String[] scenarioNames = {
            "高速局域网", "宽带网络", "移动网络", "卫星网络",
            "数据中心", "无线网络", "受限网络"
        };
        
        for (int i = 0; i < scenarios.length; i++) {
            CongestionControlConfig config = CongestionControlConfig.forScenario(scenarios[i]);
            
            System.out.printf("📋 %s配置:%n", scenarioNames[i]);
            System.out.printf("  • 初始拥塞窗口: %d KB%n", config.getInitialCwnd() / 1024);
            System.out.printf("  • 最大拥塞窗口: %d MB%n", config.getMaxCwnd() / (1024 * 1024));
            System.out.printf("  • 丢包减少因子: %.2f%n", config.getLossBeta());
            System.out.printf("  • RTT突增阈值: %d ms%n", config.getRttSpikeThreshold());
            System.out.printf("  • 丢包率阈值: %.1f%%%n", config.getLossRateThreshold() * 100);
            
            // 创建一个虚拟的拥塞控制器来应用配置
            QuicCongestionControl controller = new QuicCongestionControl(System.currentTimeMillis());
            config.applyToController(controller);
            
            System.out.println();
        }
        
        System.out.println("✅ 拥塞控制配置演示完成\n");
    }
    
    /**
     * 演示综合场景
     */
    private static void demonstrateComprehensiveScenario() throws InterruptedException {
        System.out.println("🌐 演示4: 综合场景模拟");
        System.out.println("-".repeat(60));
        System.out.println("📋 模拟场景: 3个连接，不同网络环境，每秒15MB总流量限制");
        System.out.println();
        
        GlobalFlowControl globalControl = GlobalFlowControl.getInstance();
        ExecutorService executor = Executors.newFixedThreadPool(3);
        CountDownLatch latch = new CountDownLatch(3);
        
        // 连接1: 高速局域网
        executor.submit(() -> simulateConnectionWithScenario(
                globalControl, 1L, 
                CongestionControlConfig.CongestionScenario.HIGH_SPEED_LAN, 
                latch));
        
        // 连接2: 移动网络
        executor.submit(() -> simulateConnectionWithScenario(
                globalControl, 2L, 
                CongestionControlConfig.CongestionScenario.MOBILE, 
                latch));
        
        // 连接3: 无线网络
        executor.submit(() -> simulateConnectionWithScenario(
                globalControl, 3L, 
                CongestionControlConfig.CongestionScenario.WIRELESS, 
                latch));
        
        // 监控线程
        ScheduledExecutorService monitor = Executors.newScheduledThreadPool(1);
        monitor.scheduleAtFixedRate(() -> {
            System.out.printf("  📊 全局状态: 利用率=%.1f%%, 当前秒流量=%s/%s, 活跃连接=%d%n",
                    globalControl.getCurrentFlowUtilization(),
                    formatBytes(globalControl.getCurrentSecondBytes()),
                    formatBytes(globalControl.getTargetBytesPerSecond()),
                    globalControl.getActiveConnectionCount());
        }, 2, 2, TimeUnit.SECONDS);
        
        try {
            latch.await(DEMO_DURATION_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        if (!latch.await(0, TimeUnit.MILLISECONDS)) {
            System.out.println("  ⏰ 演示时间结束");
        }
        
        monitor.shutdown();
        executor.shutdown();
        
        System.out.println("  📊 最终全局统计: " + globalControl.getGlobalStats());
        System.out.println("✅ 综合场景模拟完成\n");
    }
    
    /**
     * 模拟数据传输
     */
    private static void simulateDataTransfer(GlobalFlowControl globalControl, long connectionId) {
        QuicFlowControl flowControl = new QuicFlowControl(connectionId);
        Random random = new Random(connectionId);
        
        for (int i = 0; i < 50; i++) {
            int dataSize = 1024 + random.nextInt(4096); // 1KB-5KB
            
            if (flowControl.canSend(dataSize) && globalControl.canSendGlobally(dataSize)) {
                flowControl.onDataSent(dataSize);
                globalControl.onGlobalDataSent(dataSize);
                
                // 模拟ACK
                try {
                    Thread.sleep(10 + random.nextInt(50));
                    flowControl.onAckReceived(dataSize);
                    globalControl.onGlobalAckReceived(dataSize);
                } catch (InterruptedException e) {
                    break;
                }
                
                // 偶尔模拟丢包
                if (random.nextDouble() < 0.05) {
                    flowControl.onPacketLoss();
                }
            } else {
                try {
                    Thread.sleep(10); // 等待窗口释放
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
        
        flowControl.close();
    }
    
    /**
     * 模拟带场景的连接
     */
    private static void simulateConnectionWithScenario(
            GlobalFlowControl globalControl, 
            long connectionId,
            CongestionControlConfig.CongestionScenario scenario,
            CountDownLatch latch) {
        
        try {
            // 创建流量控制器
            QuicFlowControl flowControl = new QuicFlowControl(connectionId);
            QuicCongestionControl congestionControl = new QuicCongestionControl(connectionId);
            
            // 启动MTU发现
            flowControl.startMtuDiscovery();
            
            // 应用场景配置
            CongestionControlConfig config = CongestionControlConfig.forScenario(scenario);
            config.applyToController(congestionControl);
            
            Random random = new Random(connectionId);
            int packetsSent = 0;
            
            while (!Thread.currentThread().isInterrupted() && packetsSent < 100) {
                int dataSize = 1024 + random.nextInt(8192); // 1KB-9KB
                
                // 检查流量控制
                if (flowControl.canSend(dataSize) && 
                    globalControl.canSendGlobally(dataSize) &&
                    congestionControl.canSend(dataSize)) {
                    
                    // 发送数据
                    flowControl.onDataSent(dataSize);
                    congestionControl.onDataSent(dataSize);
                    globalControl.onGlobalDataSent(dataSize);
                    
                    packetsSent++;
                    
                    // 更新RTT
                    long rtt = 50 + random.nextInt(200); // 50-250ms RTT
                    congestionControl.updateRtt(rtt);
                    
                    // 模拟ACK
                    try {
                        Thread.sleep(rtt / 2);
                        flowControl.onAckReceived(dataSize);
                        congestionControl.onAckReceived(dataSize);
                        globalControl.onGlobalAckReceived(dataSize);
                    } catch (InterruptedException e) {
                        break;
                    }
                    
                    // 模拟丢包（根据场景）
                    double lossProbability = getLossProbability(scenario);
                    if (random.nextDouble() < lossProbability) {
                        congestionControl.onPacketLoss();
                        flowControl.onPacketLoss();
                    }
                } else {
                    try {
                        Thread.sleep(5); // 等待资源
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
            
            System.out.printf("  🔗 连接%d完成: 场景=%s, 发送包数=%d, MTU=%d%n",
                    connectionId, scenario, packetsSent, flowControl.getCurrentMtu());
            
            flowControl.close();
            
        } finally {
            latch.countDown();
        }
    }
    
    /**
     * 获取场景对应的丢包概率
     */
    private static double getLossProbability(CongestionControlConfig.CongestionScenario scenario) {
        switch (scenario) {
            case HIGH_SPEED_LAN: return 0.001;
            case BROADBAND: return 0.01;
            case MOBILE: return 0.02;
            case SATELLITE: return 0.05;
            case DATA_CENTER: return 0.0005;
            case WIRELESS: return 0.03;
            case CONSTRAINED: return 0.08;
            default: return 0.02;
        }
    }
    
    /**
     * 格式化字节数
     */
    private static String formatBytes(long bytes) {
        if (bytes == 0) return "0 B";
        String[] units = {"B", "KB", "MB", "GB"};
        int unit = 0;
        double size = bytes;
        
        while (size >= 1024 && unit < units.length - 1) {
            size /= 1024;
            unit++;
        }
        
        return String.format("%.1f %s", size, units[unit]);
    }
}