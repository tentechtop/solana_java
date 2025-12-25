package com.bit.solana.p2p.quic;

/**
 * ConnectFrameFlowController 的简单测试类
 * 验证单帧/批量帧发送控制、速率调节、在途帧统计等核心功能
 */
public class ConnectFrameFlowControllerTest {

    public static void main(String[] args) {
        // 1. 初始化流量控制器（连接ID设为1001）
        long connectionId = 1001L;
        ConnectFrameFlowController flowController = new ConnectFrameFlowController(connectionId);
/*
        System.out.println("===== 初始化流量控制器 =====");
        System.out.printf("连接ID：%d%n", flowController.getConnectionId());
        System.out.printf("初始发送速率：%d 帧/秒%n", flowController.getCurrentSendRate());
        System.out.printf("最大在途帧数量：%d%n", flowController.getMaxInFlightFrames());
        System.out.printf("初始在途帧数量：%d%n%n", flowController.getInFlightFrames());

        // 2. 测试单帧发送功能
        System.out.println("===== 测试单帧发送 =====");
        int singleSendCount = 0;
        // 循环发送单帧，直到当前秒速率耗尽
        while (flowController.canSendSingleFrame()) {
            flowController.onFrameSent();
            singleSendCount++;
            // 每发送100帧打印一次状态
            if (singleSendCount % 100 == 0) {
                System.out.printf("已发送单帧 %d 个，当前在途帧：%d，当前秒已发送：%d%n",
                        singleSendCount, flowController.getInFlightFrames(), singleSendCount);
            }
        }
        System.out.printf("单帧发送结束，共发送 %d 个（达到当前速率上限） 当前在途 %d%n%n", singleSendCount,flowController.getInFlightFrames());


        // 3. 测试单帧ACK处理（触发速率提升）
        System.out.println("===== 测试单帧ACK处理 =====");
        int singleAckCount = 0;
        // 模拟收到ACK，直到触发速率提升
        while (flowController.getInFlightFrames()!=0) {
            flowController.onFrameAcked();
            singleAckCount++;
            // 每ACK100个打印一次状态
            if (singleAckCount % 100 == 0) {
                System.out.printf("已ACK单帧 %d 个，当前在途帧：%d，当前发送速率：%d 帧/秒%n",
                        singleAckCount, flowController.getInFlightFrames(), flowController.getCurrentSendRate());
            }
        }
        System.out.printf("单帧ACK结束，共ACK %d 个，速率已提升至：%d 帧/秒%n%n",
                singleAckCount, flowController.getCurrentSendRate());

*/


        //批量发送200帧
        System.out.println("===== 批量发送200帧 =====");
        flowController.onBatchFramesSent(200);
        System.out.println("===== 在途 =====");
        System.out.printf("在途：%d%n", flowController.getInFlightFrames());

        System.out.println("===== 批量失败200帧 =====");
        //批量失败200帧
        flowController.onFrameSendFailedWithdraw(200);
        //在途
        System.out.println("===== 在途 =====");
        System.out.printf("在途：%d%n", flowController.getInFlightFrames());





/*
        // 4. 测试批量帧发送功能
        System.out.println("===== 测试批量帧发送 =====");
        int batchSize = 200; // 每次批量发送200帧
        int batchSendTimes = 0;
        int totalBatchSendCount = 0;

        while (flowController.canSendBatchFrames(batchSize)) {
            flowController.onBatchFramesSent(batchSize);
            batchSendTimes++;
            totalBatchSendCount += batchSize;
            System.out.printf("批量发送第 %d 次（每次 %d 帧），累计发送：%d 帧，当前在途帧：%d%n",
                    batchSendTimes, batchSize, totalBatchSendCount, flowController.getInFlightFrames());
        }
        System.out.printf("批量发送结束，共发送 %d 次，累计 %d 帧（达到速率/在途帧上限）%n%n",
                batchSendTimes, totalBatchSendCount);

        // 5. 测试批量帧ACK处理
        System.out.println("===== 测试批量帧ACK处理 =====");
        int ackBatchSize = 300; // 每次批量ACK300帧
        int batchAckTimes = 0;
        int totalBatchAckCount = 0;
        int originalRate = flowController.getCurrentSendRate();

        while (totalBatchAckCount < totalBatchSendCount) {
            flowController.onBatchFramesAcked(ackBatchSize);
            batchAckTimes++;
            totalBatchAckCount += ackBatchSize;
            // 避免超出总发送量
            if (totalBatchAckCount > totalBatchSendCount) {
                totalBatchAckCount = totalBatchSendCount;
            }
            System.out.printf("批量ACK第 %d 次（每次 %d 帧），累计ACK：%d 帧，当前在途帧：%d，当前速率：%d 帧/秒%n",
                    batchAckTimes, ackBatchSize, totalBatchAckCount, flowController.getInFlightFrames(),
                    flowController.getCurrentSendRate());
        }
        System.out.printf("批量ACK结束，共ACK %d 次，累计 %d 帧，速率从 %d 提升至 %d 帧/秒%n%n",
                batchAckTimes, totalBatchAckCount, originalRate, flowController.getCurrentSendRate());


        // 6. 测试帧发送失败处理
        System.out.println("===== 测试帧发送失败处理 =====");
        int failFrameCount = 150;
        System.out.printf("模拟 %d 个帧发送失败前，当前速率：%d 帧/秒%n",
                failFrameCount, flowController.getCurrentSendRate());
        // 先发送一批帧，再模拟失败
        flowController.onBatchFramesSent(failFrameCount);
        System.out.printf("发送 %d 个帧后，在途帧数量：%d%n", failFrameCount, flowController.getInFlightFrames());
        flowController.onFrameSendFailed(failFrameCount);
        System.out.printf("模拟失败后，当前速率：%d 帧/秒，当前在途帧：%d%n%n",
                flowController.getCurrentSendRate(), flowController.getInFlightFrames());

        // 7. 测试帧平均发送时间统计
        System.out.println("===== 测试帧平均发送时间统计 =====");
        // 添加5个测试时间（纳秒）
        long[] testSendTimes = {1000, 2000, 1500, 3000, 2500};
        for (long time : testSendTimes) {
            flowController.addFrameAverageSendTime(time);
        }
        long avgTime = flowController.getCurrentFrameAverageSendTime();
        System.out.printf("当前帧平均发送时间：%d 纳秒%n", avgTime);

        // 再添加6个时间，测试循环覆盖
        long[] testSendTimes2 = {4000, 3500, 5000, 4500, 6000, 5500};
        for (long time : testSendTimes2) {
            flowController.addFrameAverageSendTime(time);
        }
        long avgTimeAfterCover = flowController.getCurrentFrameAverageSendTime();
        System.out.printf("添加11个时间（循环覆盖后），当前帧平均发送时间：%d 纳秒%n", avgTimeAfterCover);*/
    }
}