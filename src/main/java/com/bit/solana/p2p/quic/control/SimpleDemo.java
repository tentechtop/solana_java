package com.bit.solana.p2p.quic.control;

import lombok.extern.slf4j.Slf4j;

/**
 * 简单演示 - 单线程测试流量控制
 * 便于理解核心逻辑
 */
@Slf4j
public class SimpleDemo {

    public static void main(String[] args) throws InterruptedException {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("流量控制系统 - 简单演示");
        System.out.println("=".repeat(70) + "\n");

        // 1. 初始化全局流量控制
        OptimizedGlobalFlowControl globalControl = OptimizedGlobalFlowControl.getInstance();

        // 设置全局带宽限制为20MB/s（给连接留足空间）
        globalControl.updateGlobalBandwidth(20 * 1024 * 1024);

        System.out.println("【配置】全局带宽限制: 20 MB/s, 连接限制: 10 MB/s\n");

        // 2. 模拟单个连接发送数据
        long connectionId = 1001;
        String ipAddress = "192.168.1.100";
        long totalSent = 0;
        int packetCount = 0;
        int dropped = 0;

        long startTime = System.currentTimeMillis();
        long testDuration = 10000; // 10秒

        System.out.println("【开始测试】模拟连接 " + connectionId + " (" + ipAddress + ") 持续发送10秒\n");

        while (System.currentTimeMillis() - startTime < testDuration) {
            // 发送512KB的数据包（更合适的大小）
            int packetSize = 512 * 1024;

            OptimizedGlobalFlowControl.OptimizedFlowControlResult result =
                    globalControl.trySend(connectionId, ipAddress, packetSize);

            if (result.isAllowed()) {
                totalSent += packetSize;
                packetCount++;

                // 模拟RTT
                long rtt = 30 + (long) (Math.random() * 50);
                globalControl.updateConnectionRtt(connectionId, rtt);

                System.out.printf("[%3d] ✓ 发送 512KB - RTT: %dms - 累计: %.2f MB\n",
                        packetCount, rtt, totalSent / 1024.0 / 1024.0);

                // 模拟网络延迟 - 减少延迟以加快测试
                Thread.sleep(50);
            } else {
                dropped++;
                System.out.printf("[XXX] ✗ 限流拒绝 (原因: %s) - 累计丢弃: %d\n",
                        result.getReason(), dropped);
                // 限流后短暂等待，让令牌桶补充
                Thread.sleep(20);
            }
        }

        // 3. 打印统计结果
        long elapsed = System.currentTimeMillis() - startTime;
        OptimizedGlobalFlowControl.OptimizedGlobalStats stats = globalControl.getGlobalStats();

        System.out.println("\n" + "=".repeat(70));
        System.out.println("【测试结果】");
        System.out.println("=".repeat(70));
        System.out.printf("测试时长: %.1f 秒\n", elapsed / 1000.0);
        System.out.printf("成功发送: %d 个包 (%.2f MB)\n", packetCount, totalSent / 1024.0 / 1024.0);
        System.out.printf("被限流: %d 次\n", dropped);
        System.out.printf("平均速率: %.2f MB/s\n", totalSent / (elapsed / 1000.0) / 1024.0 / 1024.0);
        System.out.printf("带宽利用率: %d%%\n", stats.getBandwidthUtilization());
        System.out.println("=".repeat(70));

        // 4. 评估效果
        System.out.println("\n【效果评估】");
        if (stats.getBandwidthUtilization() >= 90) {
            System.out.println("✓ 优秀！带宽利用率 ≥ 90%，网络已压榨到极致");
        } else if (stats.getBandwidthUtilization() >= 80) {
            System.out.println("✓ 良好！带宽利用率 ≥ 80%，网络利用率高");
        } else {
            System.out.println("○ 带宽利用率: " + stats.getBandwidthUtilization() + "%");
        }

        if (dropped == 0) {
            System.out.println("✓ 零限流！流量控制平滑流畅");
        } else {
            double dropRate = dropped * 100.0 / (packetCount + dropped);
            System.out.printf("丢包率: %.2f%%\n", dropRate);
        }
    }
}
