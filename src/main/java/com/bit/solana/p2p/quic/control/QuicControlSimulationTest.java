package com.bit.solana.p2p.quic.control;

import lombok.extern.slf4j.Slf4j;

/**
 * 流量控制&拥塞控制联合测试类
 */
@Slf4j
public class QuicControlSimulationTest {
    // 模拟连接ID
    private static final long TEST_CONNECTION_ID = 10001;
    // 模拟单次发送数据大小（1KB）
    private static final int SEND_DATA_SIZE = 1024;
    // 模拟RTT时间（50ms）
    private static final long SIMULATE_RTT = 50;

    public static void main(String[] args) throws InterruptedException {
        log.info("===== 开始QUIC流量控制&拥塞控制模拟测试 =====");

        // 1. 初始化两个控制器（对应同一个连接）
        QuicFlowControl flowControl = new QuicFlowControl(TEST_CONNECTION_ID);
        QuicCongestionControl congestionControl = new QuicCongestionControl(TEST_CONNECTION_ID);
        printInitState(flowControl, congestionControl);

        // 2. 阶段1：慢启动阶段 - 连续发送数据（观察cwnd指数增长、流量窗口变化）
        log.info("\n===== 阶段1：慢启动阶段 =====");
        int slowStartSendTimes = 8; // 发送8次，观察cwnd增长
        for (int i = 1; i <= slowStartSendTimes; i++) {
            log.info("\n--- 第{}次发送数据 ---", i);
            // 检查是否可以发送（同时满足流量控制和拥塞控制）
            boolean flowAllow = flowControl.canSend(SEND_DATA_SIZE);
            boolean congestionAllow = congestionControl.canSend(SEND_DATA_SIZE);
            log.info("流量控制允许发送: {}, 拥塞控制允许发送: {}", flowAllow, congestionAllow);

            if (flowAllow && congestionAllow) {
                // 发送数据
                flowControl.onDataSent(SEND_DATA_SIZE);
                congestionControl.onDataSent(SEND_DATA_SIZE);

                // 模拟RTT延时（网络往返时间）
                Thread.sleep(SIMULATE_RTT);

                // 模拟收到ACK（确认全部发送的数据）
                flowControl.onAckReceived(SEND_DATA_SIZE);
                congestionControl.onAckReceived(SEND_DATA_SIZE);

                // 模拟更新RTT（每次ACK携带RTT样本，随机波动5ms内）
                long rttSample = SIMULATE_RTT + (long) (Math.random() * 5);
                congestionControl.updateRtt(rttSample);

                // 打印当前状态
                printCurrentState(flowControl, congestionControl);
            } else {
                log.warn("第{}次发送被限制，跳过", i);
                break;
            }
        }

        // 3. 阶段2：拥塞避免阶段 - 继续发送（观察cwnd平滑增长）
        log.info("\n===== 阶段2：拥塞避免阶段 =====");
        int congestionAvoidSendTimes = 5;
        for (int i = 1; i <= congestionAvoidSendTimes; i++) {
            log.info("\n--- 第{}次发送数据（拥塞避免）---", i);
            boolean flowAllow = flowControl.canSend(SEND_DATA_SIZE);
            boolean congestionAllow = congestionControl.canSend(SEND_DATA_SIZE);

            if (flowAllow && congestionAllow) {
                flowControl.onDataSent(SEND_DATA_SIZE);
                congestionControl.onDataSent(SEND_DATA_SIZE);

                Thread.sleep(SIMULATE_RTT);

                flowControl.onAckReceived(SEND_DATA_SIZE);
                congestionControl.onAckReceived(SEND_DATA_SIZE);

                long rttSample = SIMULATE_RTT + (long) (Math.random() * 10); // RTT波动略大
                congestionControl.updateRtt(rttSample);

                printCurrentState(flowControl, congestionControl);
            } else {
                log.warn("拥塞避免阶段第{}次发送被限制，跳过", i);
                break;
            }
        }

        // 4. 阶段3：模拟丢包 - 观察窗口缩小
        log.info("\n===== 阶段3：模拟丢包场景 =====");
        log.info("触发丢包事件...");
        flowControl.onPacketLoss();
        congestionControl.onPacketLoss();
        printCurrentState(flowControl, congestionControl);

        // 5. 阶段4：模拟数据接收&处理 - 观察接收窗口变化
        log.info("\n===== 阶段4：数据接收&处理场景 =====");
        int receiveDataSize = 2048; // 单次接收2KB
        int receiveTimes = 3;
        for (int i = 1; i <= receiveTimes; i++) {
            log.info("\n--- 第{}次接收数据 ---", i);
            boolean canReceive = flowControl.canReceive(receiveDataSize);
            log.info("接收窗口是否允许接收: {}", canReceive);

            if (canReceive) {
                flowControl.onDataReceived(receiveDataSize);
                log.info("接收后流量控制器状态: {}", flowControl.getStats());

                // 模拟数据处理（延迟后恢复接收窗口）
                Thread.sleep(100);
                flowControl.onDataProcessed(receiveDataSize);
                log.info("处理后接收窗口恢复: {}", flowControl.getStats());
            } else {
                log.warn("第{}次接收被限制，跳过", i);
                break;
            }
        }

        // 6. 阶段5：重置控制器
        log.info("\n===== 阶段5：重置控制器 =====");
        flowControl.reset();
        congestionControl.reset();
        printCurrentState(flowControl, congestionControl);

        log.info("\n===== 模拟测试结束 =====");
    }

    /**
     * 打印初始状态
     */
    private static void printInitState(QuicFlowControl flowControl, QuicCongestionControl congestionControl) {
        log.info("\n--- 初始化状态 ---");
        log.info("流量控制器初始状态: {}", flowControl.getStats());
        log.info("拥塞控制器初始状态: {}", congestionControl.getStats());
    }

    /**
     * 打印当前状态
     */
    private static void printCurrentState(QuicFlowControl flowControl, QuicCongestionControl congestionControl) {
        log.info("流量控制器当前状态: {}", flowControl.getStats());
        log.info("拥塞控制器当前状态: {}", congestionControl.getStats());
    }
}