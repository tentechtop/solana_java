package com.bit.solana.p2p.quic.control;

import lombok.extern.slf4j.Slf4j;

import java.util.Random;
import java.util.concurrent.TimeUnit;

@Slf4j
public class AdvancedFlowControllerMainTest {

    // 测试参数配置
    private static final long INIT_RATE = 10 * 1024 * 1024;   // 初始速率：1MB/s
    private static final long MAX_RATE = 100 * 1024 * 1024; // 最大速率：5MB/s
    private static final long MIN_RATE = 256 * 1024;     // 最小速率：256KB/s
    private static final long MAX_BURST = 10 * 1024 * 1024; // 最大突发：2MB
    private static final double GROWTH_FACTOR = 1.2;     // 增长因子
    private static final double DECREASE_FACTOR = 0.8;   // 下降因子

    public static void main(String[] args) throws InterruptedException {
        // 初始化流量控制器
        AdvancedFlowController controller = new AdvancedFlowController(
                INIT_RATE, MAX_RATE, MIN_RATE, MAX_BURST, GROWTH_FACTOR, DECREASE_FACTOR);

        System.out.println("=== 开始流量控制测试 ===");
        System.out.println("初始状态: " + controller.getStats().toString());

        controller.updateRtt(30);

        //发送10次 for循环
        for (int i = 0; i < 10; i++) {
            boolean success = controller.trySend(1024*1024);
            if (success) {
                System.out.println("数据发送成功，大小：" + 1024*1024 + "字节");
            } else {
                System.out.println("令牌不足，数据发送失败（或部分发送）");
            }
        }


        //等待一秒再看
        TimeUnit.SECONDS.sleep(1);
        AdvancedFlowStats stats = controller.getStats();
        System.out.println(stats.toString());

        //等待一秒再看
        TimeUnit.SECONDS.sleep(1);
        AdvancedFlowStats stats1 = controller.getStats();
        System.out.println(stats1.toString());

        //等待一秒再看
        TimeUnit.SECONDS.sleep(1);
        AdvancedFlowStats stats2 = controller.getStats();
        System.out.println(stats2.toString());


        // 测试场景2：突发大流量
        System.out.println("\n=== 场景2：突发大流量 ===");
        simulateDataTransfer(controller, 5, 1024 * 1024 * 5, 100); // 5次，每次1MB，间隔100ms
        printStats(controller);

        // 测试场景3：网络拥塞（高RTT）
        System.out.println("\n=== 场景3：网络拥塞（高RTT） ===");
        for (int i = 0; i < 10; i++) {
            controller.updateRtt(200); // 模拟200ms高RTT
            simulateDataTransfer(controller, 20, 1024 * 80, 80);
        }
        printStats(controller);

        // 测试场景4：网络恢复（RTT降低）
        System.out.println("\n=== 场景4：网络恢复（低RTT） ===");
        for (int i = 0; i < 10; i++) {
            controller.updateRtt(30); // 模拟30ms低RTT
            simulateDataTransfer(controller, 20, 1024 * 60, 60);
        }
        printStats(controller);

        // 测试场景5：BBR探测机制
        System.out.println("\n=== 场景5：BBR带宽探测 ===");
        controller.updateDeliveryRate(3 * 1024 * 1024); // 模拟3MB/s传输速率
        controller.bbrProbe();
        simulateDataTransfer(controller, 50, 1024 * 70, 70);
        printStats(controller);

        // 重置控制器
        controller.reset();
        System.out.println("\n=== 重置后状态 ===");
        printStats(controller);
    }

    /**
     * 模拟数据传输
     * @param controller 流量控制器
     * @param times 传输次数
     * @param dataSize 每次传输数据大小（字节）
     * @param interval 间隔时间（毫秒）
     */
    private static void simulateDataTransfer(AdvancedFlowController controller,
                                             int times, long dataSize, long interval) throws InterruptedException {
        Random random = new Random();
        for (int i = 0; i < times; i++) {
            // 随机选择快速路径或标准路径发送
            boolean success = random.nextBoolean()
                    ? controller.trySendFast(dataSize)
                    : controller.trySend(dataSize);

            // 模拟传输速率反馈（随机波动）
            long currentRate = controller.getSendRate();
            long simulatedDelivery = (long) (currentRate * (0.9 + random.nextDouble() * 0.2));
            controller.updateDeliveryRate(simulatedDelivery);

            if (i % 1 == 0) { // 每10次打印一次进度
                log.info("第{}次传输: {} (速率: {} MB/s)",
                        i, success ? "成功" : "失败", currentRate / 1024.0 / 1024.0);
            }

            TimeUnit.MILLISECONDS.sleep(interval);
        }
    }

    /**
     * 打印当前统计信息
     */
    private static void printStats(AdvancedFlowController controller) {
        AdvancedFlowStats stats = controller.getStats();
        System.out.println("当前统计信息:");
        System.out.println(stats.toString());
    }
}