package com.bit.solana.p2p.quic;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 全局帧流量控制（所有连接汇总粒度）
 * 每帧最大1K，限制全局最大发送速度（带宽）和全局在途帧数量
 */
public class GlobalFrameFlowController {
    // 全局单例（确保所有连接共享同一个全局流量控制器）
    private static volatile GlobalFrameFlowController INSTANCE;

    // 帧大小：1K（字节），固定配置
    private static final int FRAME_SIZE_BYTES = 1024; // 1KB per frame
    // 全局最大在途帧数量
    private final int globalMaxInFlightFrames;
    // 全局最大发送带宽（字节/秒），可配置（例如：10MB/s = 10 * 1024 * 1024）
    private final int globalMaxBandwidthBytesPerSec;
    // 全局最大发送速率（帧/秒）= 全局最大带宽 / 每帧大小
    private final int globalMaxSendRate;

    // 存储所有连接的流量控制器（ConcurrentHashMap保证线程安全）
    private final Map<Long, ConnectFrameFlowController> connectionFlowControllers = new ConcurrentHashMap<>();
    // 全局当前在途帧数量（汇总所有连接的在途帧）
    private final AtomicInteger globalInFlightFrames = new AtomicInteger(0);
    // 全局当前秒发送帧数量（用于控制全局速率）
    private final AtomicInteger globalFramesSentInCurrentSecond = new AtomicInteger(0);
    // 全局当前秒时间戳
    private volatile long globalCurrentSecondTimestamp;

    /**
     * 私有构造方法（单例模式）
     * @param globalMaxBandwidthBytesPerSec 全局最大发送带宽（字节/秒）
     * @param globalMaxInFlightFrames 全局最大在途帧数量
     */
    private GlobalFrameFlowController(int globalMaxBandwidthBytesPerSec, int globalMaxInFlightFrames) {
        this.globalMaxBandwidthBytesPerSec = globalMaxBandwidthBytesPerSec;
        this.globalMaxInFlightFrames = globalMaxInFlightFrames;
        // 计算全局最大发送速率（帧/秒），向下取整
        this.globalMaxSendRate = globalMaxBandwidthBytesPerSec / FRAME_SIZE_BYTES;
        this.globalCurrentSecondTimestamp = System.currentTimeMillis() / 1000;
    }

    /**
     * 获取全局流量控制器单例
     * @param globalMaxBandwidthBytesPerSec 全局最大发送带宽（字节/秒）
     * @param globalMaxInFlightFrames 全局最大在途帧数量
     * @return 全局流量控制器单例
     */
    public static GlobalFrameFlowController getInstance(int globalMaxBandwidthBytesPerSec, int globalMaxInFlightFrames) {
        if (INSTANCE == null) {
            synchronized (GlobalFrameFlowController.class) {
                if (INSTANCE == null) {
                    INSTANCE = new GlobalFrameFlowController(globalMaxBandwidthBytesPerSec, globalMaxInFlightFrames);
                }
            }
        }
        return INSTANCE;
    }

    /**
     * 简化获取单例（使用默认配置：10MB/s带宽，65536个全局在途帧）
     * @return 全局流量控制器单例
     */
    public static GlobalFrameFlowController getDefaultInstance() {
        // 默认带宽：10MB/s = 10 * 1024 * 1024 = 10485760 字节/秒
        int defaultBandwidth = 10 * 1024 * 1024;
        // 默认全局最大在途帧：65536（8倍单连接上限）
        int defaultMaxInFlight = 65536;
        return getInstance(defaultBandwidth, defaultMaxInFlight);
    }

    /**
     * 为新连接创建端到端流量控制器，并注册到全局
     * @param connectionId 连接唯一标识
     * @return 该连接的端到端流量控制器
     */
    public ConnectFrameFlowController registerConnection(long connectionId) {
        if (connectionFlowControllers.containsKey(connectionId)) {
            throw new IllegalArgumentException("连接[" + connectionId + "]已存在，无需重复注册");
        }
        ConnectFrameFlowController controller = new ConnectFrameFlowController(connectionId);
        connectionFlowControllers.put(connectionId, controller);
        return controller;
    }

    /**
     * 移除已断开的连接，清理资源
     * @param connectionId 连接唯一标识
     */
    public void unregisterConnection(long connectionId) {
        ConnectFrameFlowController controller = connectionFlowControllers.remove(connectionId);
        if (controller != null) {
            // 从全局在途帧中减去该连接的在途帧数量
            globalInFlightFrames.addAndGet(-controller.getInFlightFrames());
        }
    }

    /**
     * 判断全局是否允许单个帧发送（需同时满足全局限制和单连接限制）
     * @param connectionId 连接唯一标识
     * @return true-允许发送，false-不允许发送
     */
    public boolean canSendSingleFrame(long connectionId) {
        // 1. 检查全局限制
        if (!checkGlobalLimit(1)) {
            return false;
        }
        // 2. 检查单连接限制
        ConnectFrameFlowController controller = connectionFlowControllers.get(connectionId);
        return controller != null && controller.canSendSingleFrame();
    }

    /**
     * 判断全局是否允许批量帧发送（需同时满足全局限制和单连接限制）
     * @param connectionId 连接唯一标识
     * @param batchSize 批量发送的帧数量
     * @return true-允许批量发送，false-不允许批量发送
     */
    public boolean canSendBatchFrames(long connectionId, int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("批量发送的帧数量不能小于等于0");
        }
        // 1. 检查全局限制
        if (!checkGlobalLimit(batchSize)) {
            return false;
        }
        // 2. 检查单连接限制
        ConnectFrameFlowController controller = connectionFlowControllers.get(connectionId);
        return controller != null && controller.canSendBatchFrames(batchSize);
    }

    /**
     * 单个帧发送成功后，更新全局统计信息
     * @param connectionId 连接唯一标识
     */
    public void onFrameSent(long connectionId) {
        ConnectFrameFlowController controller = connectionFlowControllers.get(connectionId);
        if (controller == null) {
            throw new IllegalArgumentException("连接[" + connectionId + "]未注册，无法更新发送统计");
        }
        // 1. 更新单连接统计
        controller.onFrameSent();
        // 2. 更新全局统计
        updateGlobalSecondTimestamp();
        globalFramesSentInCurrentSecond.incrementAndGet();
        globalInFlightFrames.incrementAndGet();
    }

    /**
     * 批量帧发送成功后，更新全局统计信息
     * @param connectionId 连接唯一标识
     * @param batchSize 已发送的批量帧数量
     */
    public void onBatchFramesSent(long connectionId, int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("已发送的批量帧数量不能小于等于0");
        }
        ConnectFrameFlowController controller = connectionFlowControllers.get(connectionId);
        if (controller == null) {
            throw new IllegalArgumentException("连接[" + connectionId + "]未注册，无法更新发送统计");
        }
        // 1. 更新单连接统计
        controller.onBatchFramesSent(batchSize);
        // 2. 更新全局统计
        updateGlobalSecondTimestamp();
        globalFramesSentInCurrentSecond.addAndGet(batchSize);
        globalInFlightFrames.addAndGet(batchSize);
    }

    /**
     * 单个帧收到ACK后，更新全局统计信息
     * @param connectionId 连接唯一标识
     */
    public void onFrameAcked(long connectionId) {
        ConnectFrameFlowController controller = connectionFlowControllers.get(connectionId);
        if (controller == null) {
            throw new IllegalArgumentException("连接[" + connectionId + "]未注册，无法更新ACK统计");
        }
        // 1. 更新单连接统计
        controller.onFrameAcked();
        // 2. 更新全局在途帧数量（保证不小于0）
        globalInFlightFrames.updateAndGet(count -> Math.max(count - 1, 0));
    }

    /**
     * 批量帧收到ACK后，更新全局统计信息
     * @param connectionId 连接唯一标识
     * @param batchSize 已ACK的批量帧数量
     */
    public void onBatchFramesAcked(long connectionId, int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("已ACK的批量帧数量不能小于等于0");
        }
        ConnectFrameFlowController controller = connectionFlowControllers.get(connectionId);
        if (controller == null) {
            throw new IllegalArgumentException("连接[" + connectionId + "]未注册，无法更新ACK统计");
        }
        // 1. 更新单连接统计
        controller.onBatchFramesAcked(batchSize);
        // 2. 更新全局在途帧数量（保证不小于0）
        globalInFlightFrames.updateAndGet(count -> Math.max(count - batchSize, 0));
    }

    /**
     * 帧发送失败/超时，更新全局关联的单连接统计
     * @param connectionId 连接唯一标识
     */
    public void onFrameSendFailed(long connectionId) {
        ConnectFrameFlowController controller = connectionFlowControllers.get(connectionId);
        if (controller != null) {
            controller.onFrameSendFailed();
        }
    }

    /**
     * 检查全局流量限制（在途帧、发送速率）
     * @param frameCount 待发送的帧数量
     * @return true-满足全局限制，false-超出全局限制
     */
    private boolean checkGlobalLimit(int frameCount) {
        // 1. 检查全局在途帧是否超限
        if (globalInFlightFrames.get() + frameCount > globalMaxInFlightFrames) {
            return false;
        }
        // 2. 检查全局发送速率是否超限（先更新时间戳）
        updateGlobalSecondTimestamp();
        return globalFramesSentInCurrentSecond.get() + frameCount <= globalMaxSendRate;
    }

    /**
     * 更新全局当前秒时间戳（处理跨秒场景，重置全局秒发送计数）
     */
    private void updateGlobalSecondTimestamp() {
        long currentTime = System.currentTimeMillis() / 1000;
        if (currentTime != globalCurrentSecondTimestamp) {
            globalCurrentSecondTimestamp = currentTime;
            globalFramesSentInCurrentSecond.set(0);
        }
    }

    // ------------------- 全局统计 Getter 方法 -------------------
    public int getGlobalInFlightFrames() {
        return globalInFlightFrames.get();
    }

    public int getGlobalMaxInFlightFrames() {
        return globalMaxInFlightFrames;
    }

    public int getGlobalMaxSendRate() {
        return globalMaxSendRate;
    }

    public int getGlobalFramesSentInCurrentSecond() {
        return globalFramesSentInCurrentSecond.get();
    }

    public Map<Long, ConnectFrameFlowController> getConnectionFlowControllers() {
        // 返回不可修改的Map，防止外部修改内部存储
        return Collections.unmodifiableMap(connectionFlowControllers);
    }

    public int getFrameSizeBytes() {
        return FRAME_SIZE_BYTES;
    }

    public int getGlobalMaxBandwidthBytesPerSec() {
        return globalMaxBandwidthBytesPerSec;
    }
}