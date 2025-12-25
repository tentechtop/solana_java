package com.bit.solana.p2p.quic;

import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 全局帧流量控制（纯帧控制，无带宽转换）
 * 与单连接流量控制器协作，双层限制：单连接限制 + 全局限制
 * 全局控制维度：全局最大在途帧数量 + 全局最大发送速率（帧/秒）
 */
@Slf4j
public class GlobalFrameFlowController {
    // 全局单例（确保所有连接共享同一个全局流量控制器）
    private static volatile GlobalFrameFlowController INSTANCE;

    // 全局核心配置
    private final int globalMaxInFlightFrames;       // 全局最大在途帧数量
    private final int globalMaxSendRate;             // 全局最大发送速率（帧/秒）

    // 存储所有连接的流量控制器（ConcurrentHashMap保证线程安全）
    private final Map<Long, ConnectFrameFlowController> connectionFlowControllers = new ConcurrentHashMap<>();
    // 全局当前在途帧数量（汇总所有连接的在途帧）
    private final AtomicInteger globalInFlightFrames = new AtomicInteger(0);
    // 全局当前秒发送帧数量（用于控制全局速率）
    private final AtomicInteger globalFramesSentInCurrentSecond = new AtomicInteger(0);
    // 全局当前秒时间戳
    private volatile long globalCurrentSecondTimestamp;

    /**
     * 私有构造方法
     * @param globalMaxSendRate 全局最大发送速率（帧/秒）
     * @param globalMaxInFlightFrames 全局最大在途帧数量
     */
    private GlobalFrameFlowController(int globalMaxSendRate, int globalMaxInFlightFrames) {
        this.globalMaxSendRate = globalMaxSendRate;
        this.globalMaxInFlightFrames = globalMaxInFlightFrames;
        this.globalCurrentSecondTimestamp = System.currentTimeMillis() / 1000;
        // 初始化日志（纯帧配置，一目了然）
        log.info("全局流量控制器初始化完成：全局最大发送速率{}帧/秒，全局最大在途帧{}",
                globalMaxSendRate, globalMaxInFlightFrames);
    }

    /**
     * 获取全局流量控制器单例（自定义配置）
     * @param globalMaxSendRate 全局最大发送速率（帧/秒）
     * @param globalMaxInFlightFrames 全局最大在途帧数量
     * @return 全局流量控制器单例
     */
    public static GlobalFrameFlowController getInstance(int globalMaxSendRate, int globalMaxInFlightFrames) {
        if (INSTANCE == null) {
            synchronized (GlobalFrameFlowController.class) {
                if (INSTANCE == null) {
                    INSTANCE = new GlobalFrameFlowController(globalMaxSendRate, globalMaxInFlightFrames);
                }
            }
        }
        return INSTANCE;
    }

    /**
     * 简化获取单例（默认配置，纯帧）
     * 默认：全局最大速率81920帧/秒，全局最大在途帧65536（8倍单连接上限）
     */
    public static GlobalFrameFlowController getDefaultInstance() {
        int defaultGlobalMaxSendRate = 81920;  // 全局每秒最多发81920帧
        int defaultGlobalMaxInFlight = 65536;  // 全局最多65536个在途帧
        return getInstance(defaultGlobalMaxSendRate, defaultGlobalMaxInFlight);
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
        log.debug("连接[{}]已注册，当前全局连接数：{}", connectionId, connectionFlowControllers.size());
        return controller;
    }

    /**
     * 移除已断开的连接，清理资源
     * @param connectionId 连接唯一标识
     */
    public void unregisterConnection(long connectionId) {
        ConnectFrameFlowController controller = connectionFlowControllers.remove(connectionId);
        if (controller != null) {
            // 清理全局在途帧，保证不小于0
            int connInFlight = controller.getInFlightFrames();
            globalInFlightFrames.updateAndGet(count -> Math.max(count - connInFlight, 0));
            log.debug("连接[{}]已注销，清理在途帧{}，当前全局连接数：{}",
                    connectionId, connInFlight, connectionFlowControllers.size());
        }
    }

    /**
     * 判断全局是否允许单个帧发送（核心协作：单连接限制 + 全局限制 都满足才放行）
     * @param connectionId 连接唯一标识
     * @return true-允许发送，false-不允许
     */
    public boolean canSendSingleFrame(long connectionId) {
        // 1. 校验连接是否注册
        ConnectFrameFlowController connController = connectionFlowControllers.get(connectionId);
        if (connController == null) {
            log.warn("连接[{}]未注册，无法发送单个帧", connectionId);
            return false;
        }

        // 2. 先过单连接限制，再全局限制（双层校验，缺一不可）
        return connController.canSendSingleFrame() && checkGlobalSingleFrameLimit();
    }

    /**
     * 判断全局是否允许批量帧发送（核心协作：单连接+全局双层限制）
     * @param connectionId 连接唯一标识
     * @param batchSize 批量发送的帧数量
     * @return true-允许批量发送，false-不允许
     */
    public boolean canSendBatchFrames(long connectionId, int batchSize) {
        // 1. 合法性校验
        if (batchSize <= 0) {
            throw new IllegalArgumentException("批量发送帧数量必须大于0");
        }
        // 2. 校验连接是否注册
        ConnectFrameFlowController connController = connectionFlowControllers.get(connectionId);
        if (connController == null) {
            log.warn("连接[{}]未注册，无法批量发送{}个帧", connectionId, batchSize);
            return false;
        }

        // 3. 双层校验：单连接能发 + 全局能承载
        return connController.canSendBatchFrames(batchSize) && checkGlobalBatchFramesLimit(batchSize);
    }

    /**
     * 添加连接下的帧平均时间
     */
    public void addFrameAverageSendTime(long connectionId, long frameAverageSendTime) {
        ConnectFrameFlowController controller = connectionFlowControllers.get(connectionId);
        if (controller == null) {
            throw new IllegalArgumentException("连接[" + connectionId + "]未注册，无法添加帧平均发送时间");
        }
        controller.addFrameAverageSendTime(frameAverageSendTime);
    }

    /**
     * 单个帧发送成功后，同步更新 单连接+全局 统计
     * @param connectionId 连接唯一标识
     */
    public void onFrameSent(long connectionId) {
        ConnectFrameFlowController controller = connectionFlowControllers.get(connectionId);
        if (controller == null) {
            throw new IllegalArgumentException("连接[" + connectionId + "]未注册，无法更新发送统计");
        }
        // 先更单连接，再更全局（顺序不影响，保证原子性即可）
        controller.onFrameSent();
        updateGlobalSecondTimestamp();
        globalFramesSentInCurrentSecond.incrementAndGet();
        globalInFlightFrames.incrementAndGet();
        log.trace("连接[{}]单帧发送成功，全局在途帧：{}，当前秒发送：{}",
                connectionId, globalInFlightFrames.get(), globalFramesSentInCurrentSecond.get());
    }

    /**
     * 批量帧发送成功后，同步更新 单连接+全局 统计
     * @param connectionId 连接唯一标识
     * @param batchSize 已发送的批量帧数量
     */
    public void onBatchFramesSent(long connectionId, int batchSize) {
        if (batchSize <= 0) {
            log.warn("批量发送帧数量不合法：{}，忽略更新", batchSize);
            return;
        }
        ConnectFrameFlowController controller = connectionFlowControllers.get(connectionId);
        if (controller == null) {
            throw new IllegalArgumentException("连接[" + connectionId + "]未注册，无法更新发送统计");
        }
        // 单连接统计 + 全局统计同步更新
        controller.onBatchFramesSent(batchSize);
        updateGlobalSecondTimestamp();
        globalFramesSentInCurrentSecond.addAndGet(batchSize);
        globalInFlightFrames.addAndGet(batchSize);
        log.trace("连接[{}]批量{}帧发送成功，全局在途帧：{}，当前秒发送：{}",
                connectionId, batchSize, globalInFlightFrames.get(), globalFramesSentInCurrentSecond.get());
    }

    /**
     * 单个帧ACK后，同步更新 单连接+全局 统计
     * @param connectionId 连接唯一标识
     */
    public void onFrameAcked(long connectionId) {
        ConnectFrameFlowController controller = connectionFlowControllers.get(connectionId);
        if (controller == null) {
            throw new IllegalArgumentException("连接[" + connectionId + "]未注册，无法更新ACK统计");
        }
        controller.onFrameAcked();
        // 全局在途帧递减，保底0
        globalInFlightFrames.updateAndGet(count -> Math.max(count - 1, 0));
        log.trace("连接[{}]单帧ACK完成，全局在途帧：{}", connectionId, globalInFlightFrames.get());
    }

    /**
     * 批量帧ACK后，同步更新 单连接+全局 统计
     * @param connectionId 连接唯一标识
     * @param batchSize 已ACK的批量帧数量
     */
    public void onBatchFramesAcked(long connectionId, int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("已ACK批量帧数量必须大于0");
        }
        ConnectFrameFlowController controller = connectionFlowControllers.get(connectionId);
        if (controller == null) {
            throw new IllegalArgumentException("连接[" + connectionId + "]未注册，无法更新ACK统计");
        }
        controller.onBatchFramesAcked(batchSize);
        // 全局在途帧递减，保底0（避免负数）
        globalInFlightFrames.updateAndGet(count -> Math.max(count - batchSize, 0));
        log.trace("连接[{}]批量{}帧ACK完成，全局在途帧：{}", connectionId, batchSize, globalInFlightFrames.get());
    }

    /**
     * 帧发送失败/超时（核心修正：同步撤回单连接+全局统计，释放资源）
     * @param connectionId 连接唯一标识
     * @param size 失败帧数量
     */
    public void onFrameSendFailed(long connectionId, int size) {
        // 1. 校验参数和连接合法性
        if (size <= 0) {
            throw new IllegalArgumentException("失败帧数量必须大于0");
        }
        ConnectFrameFlowController controller = connectionFlowControllers.get(connectionId);
        if (controller == null) {
            log.warn("连接[{}]未注册，无法处理帧发送失败事件", connectionId);
            return;
        }

        // 2. 先处理单连接失败逻辑（重置ACK计数+调整速率+撤回单连接统计）
        controller.onFrameSendFailed(size);
        // 3. 同步撤回全局统计（关键：释放全局占用的配额）
        onGlobalFrameSendFailedWithdraw(size);
        log.trace("连接[{}]{}帧发送失败，已撤回单连接+全局统计，触发速率下降", connectionId, size);
    }


    /**
     * 全局帧发送失败撤回：回滚全局在途帧和当前秒发送计数
     * @param withdrawCount 要撤回的帧数量
     */
    private void onGlobalFrameSendFailedWithdraw(int withdrawCount) {
        // 1. 原子性减少全局在途帧（保底0，避免负数）
        globalInFlightFrames.updateAndGet(count -> Math.max(count - withdrawCount, 0));
        // 2. 原子性减少全局当前秒发送计数（先更新时间戳，保证计数对应当前秒）
        updateGlobalSecondTimestamp();
        globalFramesSentInCurrentSecond.updateAndGet(count -> Math.max(count - withdrawCount, 0));
        log.trace("全局帧发送失败撤回：{}帧，当前全局在途帧：{}，当前秒发送帧：{}",
                withdrawCount, globalInFlightFrames.get(), globalFramesSentInCurrentSecond.get());
    }



    /**
     * 全局单帧发送限制校验（全局在途帧 + 全局速率）
     */
    private boolean checkGlobalSingleFrameLimit() {
        updateGlobalSecondTimestamp(); // 先更时间戳，保证速率计数准确
        // 1. 全局在途帧未超限  2. 全局当前秒发送速率未超限
        return globalInFlightFrames.get() < globalMaxInFlightFrames
                && globalFramesSentInCurrentSecond.get() < globalMaxSendRate;
    }

    /**
     * 全局批量帧发送限制校验（全局在途帧 + 全局速率）
     */
    private boolean checkGlobalBatchFramesLimit(int batchSize) {
        updateGlobalSecondTimestamp();
        // 1. 全局在途帧 + 待发批量 不超限  2. 全局当前秒已发 + 待发批量 不超限
        return globalInFlightFrames.get() + batchSize <= globalMaxInFlightFrames
                && globalFramesSentInCurrentSecond.get() + batchSize <= globalMaxSendRate;
    }

    /**
     * 更新全局秒时间戳（跨秒重置发送计数）
     */
    private void updateGlobalSecondTimestamp() {
        long currentTime = System.currentTimeMillis() / 1000;
        if (currentTime != globalCurrentSecondTimestamp) {
            globalCurrentSecondTimestamp = currentTime;
            globalFramesSentInCurrentSecond.set(0);
            log.trace("全局流量控制器跨秒，重置当前秒发送帧计数");
        }
    }

    // ------------------- 全局统计 Getter 方法（纯帧相关，简洁） -------------------
    public int getGlobalInFlightFrames() {
        return globalInFlightFrames.get();
    }

    public int getGlobalMaxInFlightFrames() {
        return globalMaxInFlightFrames;
    }

    public int getGlobalFramesSentInCurrentSecond() {
        return globalFramesSentInCurrentSecond.get();
    }

    public int getGlobalMaxSendRate() {
        return globalMaxSendRate;
    }

    public Map<Long, ConnectFrameFlowController> getConnectionFlowControllers() {
        return Collections.unmodifiableMap(connectionFlowControllers);
    }
}