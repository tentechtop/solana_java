package com.bit.solana.p2p.quic.control;

import lombok.extern.slf4j.Slf4j;

import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 直观演示流量控制效果
 * 目标：展示带宽压榨到极致的效果
 */
@Slf4j
public class VisualDemo {

    private static final Random random = new Random();

    public static void main(String[] args) throws InterruptedException {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("流量控制系统 - 直观演示");
        System.out.println("目标：压榨网络带宽到极致，同时保证安全稳定");
        System.out.println("=".repeat(80) + "\n");

        // 1. 初始化全局流量控制
        OptimizedGlobalFlowControl globalControl = OptimizedGlobalFlowControl.getInstance();
        
        // 设置全局带宽限制为100MB/s
        globalControl.updateGlobalBandwidth(100 * 1024 * 1024);

        System.out.println("【配置】");
        System.out.println("  全局带宽限制: 100 MB/s");
        System.out.println("  IP限制: 20 MB/s");
        System.out.println("  连接限制: 10 MB/s");
        System.out.println("  连接最小带宽: 512 KB/s\n");

        // 2. 创建3个模拟IP，每个IP有多个连接
        String[] ips = {"192.168.1.100", "192.168.1.101", "192.168.1.102"};
        int connectionsPerIp = 3;
        long connectionId = 1000;

        System.out.println("【测试场景】");
        System.out.println("  模拟IP数量: 3");
        System.out.println("  每个IP的连接数: 3");
        System.out.println("  总连接数: 9");
        System.out.println("  测试时长: 20秒\n");

        // 3. 线程池模拟并发连接
        ExecutorService executor = Executors.newFixedThreadPool(9);
        CountDownLatch latch = new CountDownLatch(9);
        AtomicLong totalSent = new AtomicLong(0);

        long startTime = System.currentTimeMillis();

        // 4. 启动所有连接
        for (String ip : ips) {
            for (int i = 0; i < connectionsPerIp; i++) {
                final long connId = connectionId++;
                final String ipAddress = ip;

                executor.submit(() -> {
                    try {
                        simulateConnection(connId, ipAddress, globalControl, totalSent, startTime);
                    } finally {
                        latch.countDown();
                    }
                });
            }
        }

        // 5. 实时监控统计（每2秒打印一次）
        new Thread(() -> {
            while (latch.getCount() > 0) {
                try {
                    Thread.sleep(2000);
                    printStats(globalControl, totalSent, startTime);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }).start();

        // 6. 等待所有连接完成
        latch.await();
        executor.shutdown();

        // 7. 最终统计
        System.out.println("\n" + "=".repeat(80));
        System.out.println("【最终统计】");
        printStats(globalControl, totalSent, startTime);
        printFinalStats(globalControl, totalSent, startTime);
        System.out.println("=".repeat(80));
    }

    /**
     * 模拟单个连接的行为
     */
    private static void simulateConnection(long connectionId, String ipAddress,
                                         OptimizedGlobalFlowControl globalControl,
                                         AtomicLong totalSent, long startTime) {
        long connSent = 0;
        int dropped = 0;
        int success = 0;

        // 每个连接持续发送20秒
        long endTime = startTime + 20000;

        while (System.currentTimeMillis() < endTime) {
            // 随机数据包大小：1KB - 100KB
            int packetSize = 1024 + random.nextInt(99 * 1024);

            // 尝试发送（三层流量控制检查）
            OptimizedGlobalFlowControl.OptimizedFlowControlResult result =
                    globalControl.trySend(connectionId, ipAddress, packetSize);

            if (result.isAllowed()) {
                connSent += packetSize;
                totalSent.addAndGet(packetSize);
                success++;

                // 模拟网络延迟（RTT）
                long rtt = 20 + random.nextInt(80); // 20-100ms
                globalControl.updateConnectionRtt(connectionId, rtt);

                // 模拟发送耗时
                try {
                    Thread.sleep(rtt / 10);
                } catch (InterruptedException e) {
                    break;
                }
            } else {
                dropped++;
                // 被限流后短暂等待
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }

        log.info("连接 {} ({}) 完成 - 发送: {}MB, 成功:{}, 丢弃:{}",
                connectionId, ipAddress,
                connSent / 1024.0 / 1024.0,
                success, dropped);

        globalControl.removeConnection(connectionId);
    }

    /**
     * 打印实时统计
     */
    private static void printStats(OptimizedGlobalFlowControl globalControl,
                                   AtomicLong totalSent, long startTime) {
        long elapsed = System.currentTimeMillis() - startTime;
        double elapsedSec = elapsed / 1000.0;

        OptimizedGlobalFlowControl.OptimizedGlobalStats stats = globalControl.getGlobalStats();

        long currentRate = (long) (totalSent.get() / elapsedSec);
        double currentRateMB = currentRate / 1024.0 / 1024.0;

        System.out.printf("\n[%.1fs] 带宽利用率: %d%% | 当前速率: %.2f MB/s | 连接数: %d\n",
                elapsedSec,
                stats.getBandwidthUtilization(),
                currentRateMB,
                stats.getActiveConnections());

        System.out.printf("      已发送: %.2f MB | 丢弃: %.2f MB | 活跃IP: %d\n",
                stats.getTotalBytesSent() / 1024.0 / 1024.0,
                stats.getDroppedBytes() / 1024.0 / 1024.0,
                stats.getActiveIps());
    }

    /**
     * 打印最终统计
     */
    private static void printFinalStats(OptimizedGlobalFlowControl globalControl,
                                        AtomicLong totalSent, long startTime) {
        OptimizedGlobalFlowControl.OptimizedGlobalStats stats = globalControl.getGlobalStats();
        long elapsed = System.currentTimeMillis() - startTime;
        double elapsedSec = elapsed / 1000.0;

        System.out.println("\n性能指标:");
        System.out.printf("  总发送数据: %.2f MB\n", totalSent.get() / 1024.0 / 1024.0);
        System.out.printf("  平均发送速率: %.2f MB/s\n", totalSent.get() / elapsedSec / 1024.0 / 1024.0);
        System.out.printf("  丢弃数据: %.2f MB (%.2f%%)\n",
                stats.getDroppedBytes() / 1024.0 / 1024.0,
                stats.getDroppedBytes() * 100.0 / (stats.getTotalBytesSent() + stats.getDroppedBytes()));
        System.out.printf("  最终带宽利用率: %d%%\n", stats.getBandwidthUtilization());
        System.out.printf("  峰值全局速率: %.2f MB/s\n", stats.getCurrentGlobalRate() / 1024.0 / 1024.0);

        System.out.println("\n流量控制效果:");
        if (stats.getBandwidthUtilization() >= 90) {
            System.out.println("  ✓ 优秀！带宽利用率 ≥ 90%，网络已压榨到极致");
        } else if (stats.getBandwidthUtilization() >= 80) {
            System.out.println("  ✓ 良好！带宽利用率 ≥ 80%，网络利用率高");
        } else if (stats.getBandwidthUtilization() >= 70) {
            System.out.println("  ○ 一般！带宽利用率 ≥ 70%，仍有提升空间");
        } else {
            System.out.println("  ✗ 需要优化！带宽利用率 < 70%");
        }

        double dropRate = stats.getDroppedBytes() * 100.0 / 
                         (stats.getTotalBytesSent() + stats.getDroppedBytes());
        if (dropRate < 1.0) {
            System.out.println("  ✓ 优秀！丢包率 < 1%，流量控制安全稳定");
        } else if (dropRate < 5.0) {
            System.out.println("  ✓ 良好！丢包率 < 5%，在可接受范围内");
        } else {
            System.out.println("  ○ 一般！丢包率 ≥ 5%，需要调优");
        }
    }
}
