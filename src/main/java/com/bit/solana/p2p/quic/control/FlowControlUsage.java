package com.bit.solana.p2p.quic.control;

import lombok.extern.slf4j.Slf4j;

/**
 * FlowController 使用示例
 */
@Slf4j
public class FlowControlUsage {
    
    public static void main(String[] args) throws InterruptedException {
        log.info("=== FlowController 使用示例 ===\n");
        
        // 1. 创建流量控制器
        // 参数: 初始速率(1MB/s), 最大速率(5MB/s), 突发大小(2MB)
        FlowController flowController = new FlowController(
            1024 * 1024,       // 初始: 1MB/s
            5 * 1024 * 1024,   // 最大: 5MB/s
            2 * 1024 * 1024     // 突发: 2MB
        );
        
        // 2. 测试基本发送
        testBasicSend(flowController);
        
        // 3. 测试流量限制
        testRateLimit(flowController);
        
        // 4. 测试速率调整
        testRateAdjustment(flowController);
        
        // 5. 显示统计信息
        displayStats(flowController);
        
        // 6. 关闭流量控制器
        flowController.shutdown();
    }
    
    /**
     * 测试基本发送
     */
    private static void testBasicSend(FlowController flowController) {
        log.info("===== 测试基本发送 =====");
        
        int successCount = 0;
        int blockedCount = 0;
        
        for (int i = 0; i < 50; i++) {
            int dataSize = 1024 * 100; // 100KB
            boolean canSend = flowController.trySend(dataSize);
            
            if (canSend) {
                successCount++;
                log.debug("发送 {} - 成功", i);
            } else {
                blockedCount++;
                log.debug("发送 {} - 被限制", i);
            }
            
            if (i % 10 == 0) {
                log.info("进度: {}/50, 成功:{}, 被限:{}", i + 1, successCount, blockedCount);
            }
        }
        
        log.info("发送完成 - 成功:{}, 被限:{}\n", successCount, blockedCount);
    }
    
    /**
     * 测试流量限制效果
     */
    private static void testRateLimit(FlowController flowController) throws InterruptedException {
        log.info("===== 测试流量限制 =====");
        
        long startTime = System.currentTimeMillis();
        long totalSent = 0;
        
        // 持续发送5秒
        while (System.currentTimeMillis() - startTime < 5000) {
            int dataSize = 1024 * 50; // 50KB
            
            if (flowController.trySend(dataSize)) {
                totalSent += dataSize;
            } else {
                Thread.sleep(10); // 被限制，等待一下
            }
        }
        
        long duration = System.currentTimeMillis() - startTime;
        double actualRate = (double) totalSent * 1000 / duration / 1024 / 1024;
        double expectedRate = flowController.getCurrentSendRate() * 1.0 / 1024 / 1024;
        
        log.info("持续发送结果:");
        log.info("  时长: {}ms", duration);
        log.info("  发送总量: {} MB", totalSent / 1024.0 / 1024.0);
        log.info("  实际速率: {} MB/s", actualRate);
        log.info("  限制速率: {} MB/s", expectedRate);
        log.info("  速率差异: {}%\n", Math.abs(actualRate - expectedRate) / expectedRate * 100);
    }
    
    /**
     * 测试速率动态调整
     */
    private static void testRateAdjustment(FlowController flowController) throws InterruptedException {
        log.info("===== 测试速率调整 =====");
        
        // 初始速率
        log.info("初始速率: {} MB/s", flowController.getCurrentSendRate() / 1024.0 / 1024.0);
        
        // 降低速率到2MB/s
        flowController.updateSendRate(2 * 1024 * 1024);
        log.info("调整后速率: {} MB/s", flowController.getCurrentSendRate() / 1024.0 / 1024.0);
        
        // 测试新速率下的限制
        Thread.sleep(100);
        long startTime = System.currentTimeMillis();
        int sentAtLowRate = 0;
        
        while (System.currentTimeMillis() - startTime < 2000) {
            if (flowController.trySend(1024 * 100)) {
                sentAtLowRate++;
            }
            Thread.sleep(5);
        }
        
        log.info("低速率下2秒内发送: {} 次 (100KB/次)", sentAtLowRate);
        
        // 提高速率到5MB/s
        flowController.updateSendRate(5 * 1024 * 1024);
        log.info("再次调整后速率: {} MB/s", flowController.getCurrentSendRate() / 1024.0 / 1024.0);
        
        // 测试新速率下的发送
        Thread.sleep(100);
        startTime = System.currentTimeMillis();
        int sentAtHighRate = 0;
        
        while (System.currentTimeMillis() - startTime < 2000) {
            if (flowController.trySend(1024 * 100)) {
                sentAtHighRate++;
            }
            Thread.sleep(5);
        }
        
        log.info("高速率下2秒内发送: {} 次 (100KB/次)", sentAtHighRate);
        log.info("速率提升效果: {}x\n", (double) sentAtHighRate / sentAtLowRate);
    }
    
    /**
     * 显示详细统计
     */
    private static void displayStats(FlowController flowController) {
        log.info("===== 流量控制统计 =====");
        FlowControlStats stats = flowController.getStats();
        
        log.info("已发送: {} MB", stats.getTotalBytesSent() / 1024.0 / 1024.0);
        log.info("已丢弃: {} MB", stats.getTotalBytesDropped() / 1024.0 / 1024.0);
        log.info("令牌耗尽: {} 次", stats.getTokensExhaustedCount());
        log.info("当前速率: {} MB/s", stats.getCurrentSendRate() / 1024.0 / 1024.0);
        log.info("最大速率: {} MB/s", stats.getMaxSendRate() / 1024.0 / 1024.0);
        log.info("可用令牌: {} / {} ({}%)",
                stats.getAvailableTokens(), 
                stats.getMaxTokens(),
                stats.getTokenUtilization() * 100);
        log.info("\n统计详情: {}", stats);
    }
}