package com.bit.solana.p2p.quic.control;

import lombok.extern.slf4j.Slf4j;

/**
 * 智能流量控制演示 - 展示平滑调节和自我恢复
 * 
 * 演示特性：
 * 1. 平滑调节 - 避免频繁抖动
 * 2. 自我恢复 - 检测竞争卡住并自动恢复
 * 3. 实时观测 - 显示网络状态和调节决策
 */
@Slf4j
public class IntelligentDemo {

    public static void main(String[] args) throws InterruptedException {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("智能流量控制系统 - 演示");
        System.out.println("特性：平滑调节 + 自我恢复 + 实时观测");
        System.out.println("=".repeat(80) + "\n");

        // 1. 初始化智能流量控制
        IntelligentGlobalFlowControl globalControl = IntelligentGlobalFlowControl.getInstance();
        
        // 设置全局带宽限制为20MB/s
        globalControl.updateGlobalBandwidth(20 * 1024 * 1024);

        System.out.println("【配置】");
        System.out.println("  全局带宽限制: 20 MB/s");
        System.out.println("  连接限制: 10 MB/s");
        System.out.println("  观测间隔: 2000ms");
        System.out.println("  调节间隔: 5000ms");
        System.out.println("  平滑阈值: 连续3次相同状态");
        System.out.println("  恢复检测: 5秒无发送触发");
        System.out.println();

        // 2. 启动监控线程
        Thread monitorThread = new Thread(() -> {
            long startTime = System.currentTimeMillis();
            while (true) {
                try {
                    Thread.sleep(2000);
                    printMonitoring(globalControl, startTime);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "Monitoring");
        monitorThread.setDaemon(true);
        monitorThread.start();

        // 3. 模拟多个连接
        long connectionId = 1001;
        String ipAddress = "192.168.1.100";
        long totalSent = 0;
        int packetCount = 0;
        int dropped = 0;

        long startTime = System.currentTimeMillis();
        long testDuration = 60000; // 60秒

        System.out.println("【开始测试】模拟连接持续发送60秒\n");

        // 第一阶段：正常发送（0-20秒）
        System.out.println(">>> 阶段1: 正常发送 (0-20秒) <<<");
        simulatePhase(globalControl, connectionId, ipAddress, 20000, 512 * 1024, 50);

        // 第二阶段：超速发送，触发限流（20-35秒）
        System.out.println("\n>>> 阶段2: 超速发送，触发限流 (20-35秒) <<<");
        simulatePhase(globalControl, connectionId, ipAddress, 15000, 1024 * 1024, 20);

        // 第三阶段：恢复正常（35-45秒）
        System.out.println("\n>>> 阶段3: 恢复正常 (35-45秒) <<<");
        simulatePhase(globalControl, connectionId, ipAddress, 10000, 512 * 1024, 50);

        // 第四阶段：模拟卡住（45-50秒）
        System.out.println("\n>>> 阶段4: 模拟卡住 (45-50秒) - 等待自我恢复 <<<");
        Thread.sleep(5000);
        System.out.println("卡住模拟完成");

        // 第五阶段：恢复正常（50-60秒）
        System.out.println("\n>>> 阶段5: 恢复正常发送 (50-60秒) <<<");
        simulatePhase(globalControl, connectionId, ipAddress, 10000, 512 * 1024, 50);

        // 6. 最终统计
        Thread.sleep(2000);
        System.out.println("\n" + "=".repeat(80));
        System.out.println("【最终统计】");
        printFinalStats(globalControl, startTime);
        System.out.println("=".repeat(80));

        // 关闭
        globalControl.shutdown();
    }

    private static void simulatePhase(IntelligentGlobalFlowControl globalControl,
                                   long connectionId, String ipAddress,
                                   long durationMs, int packetSize, int sleepMs)
            throws InterruptedException {
        long phaseStart = System.currentTimeMillis();
        long phaseEnd = phaseStart + durationMs;

        while (System.currentTimeMillis() < phaseEnd) {
            IntelligentGlobalFlowControl.OptimizedFlowControlResult result =
                    globalControl.trySend(connectionId, ipAddress, packetSize);

            if (result.isAllowed()) {
                Thread.sleep(sleepMs);
            } else {
                // 限流时短暂等待，让令牌桶补充
                Thread.sleep(50);
            }
        }
    }

    private static void printMonitoring(IntelligentGlobalFlowControl globalControl, long startTime) {
        long elapsed = System.currentTimeMillis() - startTime;
        double elapsedSec = elapsed / 1000.0;

        IntelligentGlobalFlowControl.ObservationData obs = globalControl.getObservationData();
        if (obs == null) return;

        IntelligentGlobalFlowControl.OptimizedGlobalStats stats = globalControl.getGlobalStats();

        System.out.printf("\n[%.1fs] 网络状态监控\n", elapsedSec);
        System.out.printf("  利用率: %d%% | 当前速率: %.2f MB/s | 丢包率: %s%%\n",
                obs.getBandwidthUtilization(),
                obs.getBytesPerSecond() / 1024.0 / 1024.0,
                String.format("%.2f", obs.getDropRate()));
        System.out.printf("  连接数: %d | IP数: %d | 连续丢包: %d\n",
                obs.getActiveConnections(), obs.getActiveIps(), obs.getConsecutiveDrops());
        System.out.printf("  总发送: %.2f MB | 丢弃: %.2f MB\n",
                stats.getTotalBytesSent() / 1024.0 / 1024.0,
                stats.getDroppedBytes() / 1024.0 / 1024.0);
    }

    private static void printFinalStats(IntelligentGlobalFlowControl globalControl, long startTime) {
        long elapsed = System.currentTimeMillis() - startTime;
        double elapsedSec = elapsed / 1000.0;

        IntelligentGlobalFlowControl.OptimizedGlobalStats stats = globalControl.getGlobalStats();

        System.out.printf("\n测试时长: %.1f 秒\n", elapsedSec);
        System.out.printf("总发送数据: %.2f MB\n", stats.getTotalBytesSent() / 1024.0 / 1024.0);
        System.out.printf("丢弃数据: %.2f MB (%.2f%%)\n",
                stats.getDroppedBytes() / 1024.0 / 1024.0,
                stats.getDroppedBytes() * 100.0 / (stats.getTotalBytesSent() + stats.getDroppedBytes()));
        System.out.printf("最终带宽利用率: %d%%\n", stats.getBandwidthUtilization());
        System.out.printf("当前全局速率: %.2f MB/s\n",
                stats.getCurrentGlobalRate() / 1024.0 / 1024.0);

        System.out.println("\n智能调节效果:");
        if (stats.getBandwidthUtilization() >= 85) {
            System.out.println("  ✓ 优秀！带宽利用率 ≥ 85%，智能调节有效");
        } else if (stats.getBandwidthUtilization() >= 70) {
            System.out.println("  ○ 良好！带宽利用率 ≥ 70%");
        }

        double dropRate = stats.getDroppedBytes() * 100.0 / 
                         (stats.getTotalBytesSent() + stats.getDroppedBytes());
        if (dropRate < 1.0) {
            System.out.println("  ✓ 优秀！丢包率 < 1%，流量控制平滑");
        } else if (dropRate < 3.0) {
            System.out.println("  ✓ 良好！丢包率 < 3%");
        }
    }
}
