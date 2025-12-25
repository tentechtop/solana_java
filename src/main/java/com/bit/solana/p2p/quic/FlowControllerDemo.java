package com.bit.solana.p2p.quic;

/**
 * 流量控制器使用示例
 */
public class FlowControllerDemo {
    public static void main(String[] args) throws InterruptedException {
        // 1. 获取全局流量控制器（使用默认配置：10MB/s带宽，65536全局在途帧）
        GlobalFrameFlowController globalController = GlobalFrameFlowController.getDefaultInstance();

        // 2. 注册2个QUIC连接
        long connectionId1 = 1001;
        long connectionId2 = 1002;
        ConnectFrameFlowController conn1Controller = globalController.registerConnection(connectionId1);
        ConnectFrameFlowController conn2Controller = globalController.registerConnection(connectionId2);

        // 3. 模拟连接1发送单个帧
        if (globalController.canSendSingleFrame(connectionId1)) {
            System.out.printf("连接[%d]发送单个帧成功%n", connectionId1);
            globalController.onFrameSent(connectionId1);
        } else {
            System.out.printf("连接[%d]无法发送单个帧（全局/单连接限制）%n", connectionId1);
        }

        // 4. 模拟连接2批量发送100个帧
        int batchSize = 10000;
        if (globalController.canSendBatchFrames(connectionId2, batchSize)) {
            System.out.printf("连接[%d]批量发送%d个帧成功%n", connectionId2, batchSize);
            globalController.onBatchFramesSent(connectionId2, batchSize);
        } else {
            System.out.printf("连接[%d]无法批量发送%d个帧（全局/单连接限制）%n", connectionId2, batchSize);
        }

        // 5. 模拟连接1单个帧ACK
        globalController.onFrameAcked(connectionId1);
        System.out.printf("连接[%d]单个帧ACK完成，当前单连接在途帧：%d%n", connectionId1, conn1Controller.getInFlightFrames());

        // 6. 模拟连接2批量帧ACK（50个）
        int ackBatchSize = 5000;
        globalController.onBatchFramesAcked(connectionId2, ackBatchSize);
        System.out.printf("连接[%d]批量%d个帧ACK完成，当前单连接在途帧：%d%n", connectionId2, ackBatchSize, conn2Controller.getInFlightFrames());

        // 7. 打印全局统计信息
        System.out.printf("全局当前在途帧：%d，全局最大在途帧：%d%n",
                globalController.getGlobalInFlightFrames(), globalController.getGlobalMaxInFlightFrames());
        System.out.printf("全局当前秒发送帧：%d，全局最大发送速率：%d（帧/秒）%n",
                globalController.getGlobalFramesSentInCurrentSecond(), globalController.getGlobalMaxSendRate());

        // 8. 断开连接，注销资源
        globalController.unregisterConnection(connectionId1);
        globalController.unregisterConnection(connectionId2);
        System.out.println("所有连接已注销，全局连接数：" + globalController.getConnectionFlowControllers().size());
    }
}