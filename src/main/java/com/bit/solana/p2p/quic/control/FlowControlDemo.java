package com.bit.solana.p2p.quic.control;


import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 流量控制综合演示
 * 展示连接级、IP级、全局级三层流量控制
 */
@Slf4j
public class FlowControlDemo {
    
    public static void main(String[] args) throws InterruptedException {
        log.info("=== QUIC流量控制综合演示 ===\n");
        
        // 1. 初始化全局流量控制
        GlobalFlowControl globalFlowControl = GlobalFlowControl.getInstance();
        log.info("全局流量控制初始化完成\n");
        
        // 2. 演示连接级流量控制
        demoConnectionFlowControl();
        
        // 3. 演示IP级流量控制
        demoIpFlowControl();
        
        // 4. 演示全局流量控制
        demoGlobalFlowControl();
        
        // 5. 演示综合流量控制
        demoIntegratedFlowControl();
        
        // 6. 显示最终统计
        displayFinalStats(globalFlowControl);
    }
    
    /**
     * 演示连接级流量控制
     */
    private static void demoConnectionFlowControl() throws InterruptedException {
        log.info("===== 连接级流量控制演示 =====");
        
        // 创建连接流量控制器
        FlowController flowController = new FlowController(
            1024 * 1024,       // 初始1MB/s
            10 * 1024 * 1024,   // 最大10MB/s
            5 * 1024 * 1024      // 突发5MB
        );
        
        // 模拟发送数据
        int packetSize = 1024 * 100; // 100KB
        int successCount = 0;
        int blockedCount = 0;
        
        for (int i = 0; i < 100; i++) {
            boolean success = flowController.trySend(packetSize);
            if (success) {
                successCount++;
            } else {
                blockedCount++;
            }
            
            if (i % 20 == 0) {
                log.debug("发送进度: {}/100, 成功:{}, 被限:{}", i, successCount, blockedCount);
            }
            
            Thread.sleep(10); // 模拟发送间隔
        }
        
        log.info("发送完成 - 成功:{}, 被限:{}", successCount, blockedCount);
        log.info("流量统计: {}\n", flowController.getStats());
    }
    
    /**
     * 演示IP级流量控制
     */
    private static void demoIpFlowControl() throws InterruptedException {
        log.info("===== IP级流量控制演示 =====");
        
        String[] ips = {
            "192.168.1.100",
            "192.168.1.101",
            "192.168.1.102"
        };
        
        List<IpSender> senders = new ArrayList<>();
        
        // 为每个IP创建发送者
        for (String ip : ips) {
            IpSender sender = new IpSender(ip);
            senders.add(sender);
            new Thread(sender, "Sender-" + ip).start();
        }
        
        // 运行10秒
        Thread.sleep(10000);
        
        // 停止所有发送者
        senders.forEach(IpSender::stop);
        
        // 显示统计
        for (IpSender sender : senders) {
            log.info("IP:{} - 发送:{}字节, 被限:{}次", 
                    sender.getIp(), sender.getBytesSent(), sender.getBlockedCount());
        }
        
        log.info("");
    }
    
    /**
     * 演示全局流量控制
     */
    private static void demoGlobalFlowControl() throws InterruptedException {
        log.info("===== 全局流量控制演示 =====");
        
        // 设置全局带宽限制为20MB/s
        GlobalFlowControl.getInstance().updateGlobalBandwidth(20 * 1024 * 1024);
        
        // 创建多个并发连接
        int connectionCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(connectionCount);
        
        for (int i = 0; i < connectionCount; i++) {
            final int connId = i;
            executor.submit(() -> {
                simulateConnection(connId, "192.168.1." + (100 + connId));
            });
        }
        
        // 运行15秒
        Thread.sleep(15000);
        
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
        
        // 显示全局统计
        GlobalFlowControl.GlobalFlowStats stats = GlobalFlowControl.getInstance().getGlobalStats();
        log.info("全局统计 - 发送:{}字节, 接收:{}字节, 丢弃:{}字节", 
                stats.getTotalBytesSent(), 
                stats.getTotalBytesReceived(),
                stats.getDroppedBytes());
        log.info("当前速率: {}/{} MB/s, 活跃连接:{}, 活跃IP:{}\n", 
                stats.getCurrentGlobalRate() / 1024 / 1024,
                stats.getMaxGlobalRate() / 1024 / 1024,
                stats.getActiveConnections(),
                stats.getActiveIps());
    }
    
    /**
     * 演示综合流量控制（全局+IP+连接）
     */
    private static void demoIntegratedFlowControl() throws InterruptedException {
        log.info("===== 综合流量控制演示 =====");
        
        // 设置全局限制
        GlobalFlowControl.getInstance().updateGlobalBandwidth(50 * 1024 * 1024);
        
        // 创建多个连接，每个连接模拟发送
        int connectionCount = 10;
        for (int i = 0; i < connectionCount; i++) {
            final long connId = 1000 + i;
            final String ip = "192.168.1." + (100 + i);
            
            new Thread(() -> {
                int sent = 0;
                int blocked = 0;
                long startTime = System.currentTimeMillis();
                
                while (System.currentTimeMillis() - startTime < 8000) { // 运行8秒
                    // 检查综合流量控制
                    GlobalFlowControl.FlowControlResult result = 
                        GlobalFlowControl.getInstance().trySend(connId, ip, 1024 * 64);
                    
                    if (result.isAllowed()) {
                        sent++;
                    } else {
                        blocked++;
                    }
                    
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                
                log.info("连接:{} ({}) - 发送:{}次, 被限:{}次", 
                        connId, ip, sent, blocked);
                
            }, "Conn-" + connId).start();
        }
        
        Thread.sleep(10000);
        log.info("");
    }
    
    /**
     * 显示最终统计信息
     */
    private static void displayFinalStats(GlobalFlowControl globalFlowControl) {
        log.info("===== 最终统计信息 =====");
        GlobalFlowControl.GlobalFlowStats stats = globalFlowControl.getGlobalStats();
        
        log.info("【全局统计】");
        log.info("  总发送: {} MB", stats.getTotalBytesSent() / 1024 / 1024);
        log.info("  总接收: {} MB", stats.getTotalBytesReceived() / 1024 / 1024);
        log.info("  总丢弃: {} MB", stats.getDroppedBytes() / 1024 / 1024);
        log.info("  当前速率: {} MB/s", stats.getCurrentGlobalRate() / 1024.0 / 1024.0);
        log.info("  最大速率: {} MB/s", stats.getMaxGlobalRate() / 1024.0 / 1024.0);
        log.info("  活跃连接: {}", stats.getActiveConnections());
        log.info("  活跃IP: {}", stats.getActiveIps());
    }
    
    /**
     * 模拟连接发送数据
     */
    private static void simulateConnection(int connId, String ipAddress) {
        long bytesSent = 0;
        int blocked = 0;
        long startTime = System.currentTimeMillis();
        
        while (System.currentTimeMillis() - startTime < 10000) {
            // 检查全局流量控制
            boolean allowed = GlobalFlowControl.getInstance().trySendGlobal(1024 * 100); // 100KB
            
            if (allowed) {
                bytesSent += 1024 * 100;
            } else {
                blocked++;
            }
            
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        
        log.info("连接:{} ({}) - 发送:{} MB, 被限:{}次", 
                connId, ipAddress, bytesSent / 1024 / 1024, blocked);
    }
    
    /**
     * IP发送者（用于演示IP级流量控制）
     */
    private static class IpSender implements Runnable {
        private final String ip;
        private volatile boolean running = true;
        private long bytesSent = 0;
        private int blockedCount = 0;
        private final FlowController flowController;
        
        IpSender(String ip) {
            this.ip = ip;
            this.flowController = new FlowController(
                1024 * 1024 * 2,   // 2MB/s
                1024 * 1024 * 10,   // 10MB/s最大
                1024 * 1024 * 5      // 5MB突发
            );
        }
        
        @Override
        public void run() {
            while (running) {
                boolean success = flowController.trySend(1024 * 50); // 50KB
                if (success) {
                    bytesSent += 1024 * 50;
                } else {
                    blockedCount++;
                }
                
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        
        void stop() {
            running = false;
        }
        
        String getIp() { return ip; }
        long getBytesSent() { return bytesSent; }
        int getBlockedCount() { return blockedCount; }
    }
}