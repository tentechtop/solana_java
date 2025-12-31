package com.bit.solana.p2p.quic.control;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 智能全局流量控制系统 - 带平滑机制和自我恢复能力
 * 
 * 核心特性：
 * 1. 自我观测 - 实时监控流量、延迟、丢包率
 * 2. 平滑调节 - 避免频繁抖动，使用滑动窗口平滑策略
 * 3. 自我恢复 - 检测竞争卡住，自动触发恢复机制
 * 4. 智能决策 - 基于历史数据的多层决策
 * 
 * 目标：压榨网络带宽到极致，同时保证公平和安全
 */
@Slf4j
public class IntelligentGlobalFlowControl {

    // 单例实例
    private static volatile IntelligentGlobalFlowControl instance;

    // ========== 全局流量控制 ==========
    private final AdvancedFlowController globalController;

    // ========== IP级别流量控制 ==========
    private final ConcurrentHashMap<String, AdvancedFlowController> ipControllers = new ConcurrentHashMap<>();

    // ========== 连接级别流量控制 ==========
    private final ConcurrentHashMap<Long, AdvancedFlowController> connectionControllers = new ConcurrentHashMap<>();

    // ========== 全局统计 ==========
    private final AtomicLong totalBytesSent = new AtomicLong(0);
    private final AtomicLong totalBytesReceived = new AtomicLong(0);
    private final AtomicLong droppedBytes = new AtomicLong(0);

    // ========== 配置参数 ==========
    private volatile long maxGlobalBandwidth = 1024 * 1024 * 100; // 默认100MB/s
    private volatile long maxIpBandwidth = 1024 * 1024 * 20;    // IP限制20MB/s
    private volatile long maxConnectionBandwidth = 1024 * 1024 * 10; // 连接限制10MB/s
    private volatile long minConnectionBandwidth = 1024 * 512;  // 连接最小512KB/s

    // ========== 自适应带宽分配 ==========
    private final ConcurrentHashMap<Long, Long> connectionPriority = new ConcurrentHashMap<>();
    private volatile long globalBandwidthUtilization = 0;

    // ========== 智能观测系统 ==========
    private final ObservationSystem observationSystem;
    private final ScheduledExecutorService observationScheduler;
    private final SmoothTuner smoothTuner;
    private final SelfRecoverySystem selfRecoverySystem;
    private volatile boolean autoTuningEnabled = true;
    private volatile long observationInterval = 2000; // 观测间隔2秒
    private volatile long tuningInterval = 5000; // 调节间隔5秒

    private IntelligentGlobalFlowControl() {
        // 全局控制器：初始50MB/s，最大100MB/s，最小10MB/s
        this.globalController = new AdvancedFlowController(
                50 * 1024 * 1024,   // 初始: 50MB/s
                100 * 1024 * 1024,   // 最大: 100MB/s
                10 * 1024 * 1024,    // 最小: 10MB/s
                20 * 1024 * 1024,    // 突发: 20MB
                1.1,                  // 增长因子: 1.1 (激进)
                0.8                   // 下降因子: 0.8 (适度)
        );

        // 初始化智能系统
        this.observationSystem = new ObservationSystem();
        this.smoothTuner = new SmoothTuner();
        this.selfRecoverySystem = new SelfRecoverySystem();
        this.observationScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "FlowControl-Intelligent");
            t.setDaemon(true);
            return t;
        });

        // 启动观测、调节和恢复系统
        startIntelligentSystems();

        log.info("[智能全局流控] 初始化完成 - 全局带宽:{}MB/s, IP限制:{}MB/s, 连接限制:{}MB/s",
                maxGlobalBandwidth / 1024 / 1024,
                maxIpBandwidth / 1024 / 1024,
                maxConnectionBandwidth / 1024 / 1024);
    }

    public static IntelligentGlobalFlowControl getInstance() {
        if (instance == null) {
            synchronized (IntelligentGlobalFlowControl.class) {
                if (instance == null) {
                    instance = new IntelligentGlobalFlowControl();
                }
            }
        }
        return instance;
    }

    /**
     * 启动智能系统
     */
    private void startIntelligentSystems() {
        // 1. 观测系统（每2秒）
        observationScheduler.scheduleAtFixedRate(() -> {
            try {
                observationSystem.collectObservations(this);
            } catch (Exception e) {
                log.error("[观测系统] 收集观测数据失败", e);
            }
        }, observationInterval, observationInterval, TimeUnit.MILLISECONDS);

        // 2. 智能调节（每5秒）
        observationScheduler.scheduleAtFixedRate(() -> {
            try {
                if (autoTuningEnabled) {
                    smoothTuner.performSmoothTuning(this);
                }
            } catch (Exception e) {
                log.error("[平滑调节] 智能调节失败", e);
            }
        }, tuningInterval, tuningInterval, TimeUnit.MILLISECONDS);

        // 3. 自我恢复检测（每3秒）
        observationScheduler.scheduleAtFixedRate(() -> {
            try {
                selfRecoverySystem.checkAndRecover(this);
            } catch (Exception e) {
                log.error("[自我恢复] 恢复检测失败", e);
            }
        }, 3000, 3000, TimeUnit.MILLISECONDS);

        log.info("[智能全局流控] 智能系统已启动 - 观测:{}ms, 调节:{}ms, 恢复检测:{}ms",
                observationInterval, tuningInterval, 3000);
    }

    // ========== 流量控制接口 ==========

    public boolean trySendGlobal(long dataSize) {
        boolean allowed = globalController.trySendFast(dataSize);
        if (allowed) {
            totalBytesSent.addAndGet(dataSize);
            updateUtilization();
        } else {
            droppedBytes.addAndGet(dataSize);
        }
        return allowed;
    }

    public boolean trySendByIp(String ipAddress, long dataSize) {
        AdvancedFlowController ipController = ipControllers.computeIfAbsent(ipAddress, k -> createIpController());
        boolean allowed = ipController.trySendFast(dataSize);
        if (!allowed) {
            observationSystem.recordDrop("IP");
        }
        return allowed;
    }

    public boolean trySendByConnection(long connectionId, long dataSize) {
        AdvancedFlowController connController = connectionControllers.computeIfAbsent(
                connectionId, k -> createConnectionController()
        );
        boolean allowed = connController.trySendFast(dataSize);
        if (!allowed) {
            observationSystem.recordDrop("CONNECTION");
        }
        return allowed;
    }

    public OptimizedFlowControlResult trySend(long connectionId, String ipAddress, long dataSize) {
        if (!trySendGlobal(dataSize)) {
            return OptimizedFlowControlResult.rejected("全局流量限制");
        }
        if (!trySendByIp(ipAddress, dataSize)) {
            return OptimizedFlowControlResult.rejected("IP流量限制");
        }
        if (!trySendByConnection(connectionId, dataSize)) {
            return OptimizedFlowControlResult.rejected("连接流量限制");
        }
        updateConnectionPriority(connectionId, dataSize);
        return OptimizedFlowControlResult.allowed();
    }

    // ========== 辅助方法 ==========

    private AdvancedFlowController createIpController() {
        return new AdvancedFlowController(
                maxIpBandwidth / 2, maxIpBandwidth, maxIpBandwidth / 10,
                maxIpBandwidth / 2, 1.15, 0.85
        );
    }

    private AdvancedFlowController createConnectionController() {
        return new AdvancedFlowController(
                maxConnectionBandwidth / 2, maxConnectionBandwidth, minConnectionBandwidth,
                maxConnectionBandwidth / 2, 1.2, 0.9
        );
    }

    private void updateConnectionPriority(long connectionId, long dataSize) {
        connectionPriority.compute(connectionId, (k, v) -> {
            if (v == null) return (long) dataSize;
            return v * 9 / 10 + dataSize;
        });
    }

    private void updateUtilization() {
        long currentRate = globalController.getSendRate();
        globalBandwidthUtilization = (currentRate * 100) / maxGlobalBandwidth;
    }

    public void updateConnectionRtt(long connectionId, long rtt) {
        AdvancedFlowController controller = connectionControllers.get(connectionId);
        if (controller != null) {
            controller.updateRtt(rtt);
            observationSystem.recordRtt(rtt);
        }
    }

    public void updateGlobalBandwidth(long newBandwidth) {
        this.maxGlobalBandwidth = newBandwidth;
        globalController.updateSendRate(newBandwidth);
        log.info("[智能全局流控] 带宽限制更新: {} MB/s", newBandwidth / 1024 / 1024);
    }

    public void removeConnection(long connectionId) {
        connectionControllers.remove(connectionId);
        connectionPriority.remove(connectionId);
    }

    public OptimizedGlobalStats getGlobalStats() {
        return OptimizedGlobalStats.builder()
                .totalBytesSent(totalBytesSent.get())
                .totalBytesReceived(totalBytesReceived.get())
                .droppedBytes(droppedBytes.get())
                .currentGlobalRate(globalController.getSendRate())
                .maxGlobalRate(maxGlobalBandwidth)
                .bandwidthUtilization(globalBandwidthUtilization)
                .activeConnections(connectionControllers.size())
                .activeIps(ipControllers.size())
                .globalStats(globalController.getStats())
                .build();
    }

    public ObservationData getObservationData() {
        return observationSystem.getLatestObservations();
    }

    public void setAutoTuning(boolean enabled) {
        this.autoTuningEnabled = enabled;
        log.info("[智能全局流控] 自动调节: {}", enabled ? "启用" : "禁用");
    }

    public void shutdown() {
        observationScheduler.shutdown();
        try {
            if (!observationScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                observationScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            observationScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("[智能全局流控] 智能系统已关闭");
    }

    // ========== 内部类 ==========

    /**
     * 观测系统 - 收集网络状态数据
     */
    private static class ObservationSystem {
        private final ConcurrentLinkedDeque<ObservationData> observations = new ConcurrentLinkedDeque<>();
        private static final int MAX_OBSERVATIONS = 30;
        private final AtomicLong lastSentBytes = new AtomicLong(0);
        private final AtomicLong lastDroppedBytes = new AtomicLong(0);
        private final AtomicLong lastRtt = new AtomicLong(0);
        private final AtomicLong rttCount = new AtomicLong(0);
        private volatile long lastObservationTime = 0;
        private final AtomicLong consecutiveDrops = new AtomicLong(0);

        public void collectObservations(IntelligentGlobalFlowControl control) {
            long currentTime = System.currentTimeMillis();

            long currentSent = control.totalBytesSent.get();
            long currentDropped = control.droppedBytes.get();

            long sentDelta = currentSent - lastSentBytes.getAndSet(currentSent);
            long droppedDelta = currentDropped - lastDroppedBytes.getAndSet(currentDropped);

            long timeDelta = currentTime - lastObservationTime;
            if (timeDelta == 0) return;

            lastObservationTime = currentTime;

            long bytesPerSecond = (sentDelta * 1000) / timeDelta;
            long totalBytes = sentDelta + droppedDelta;
            double dropRate = totalBytes > 0 ? (droppedDelta * 100.0 / totalBytes) : 0.0;

            long avgRtt = rttCount.get() > 0 ? lastRtt.get() / rttCount.get() : 0;
            lastRtt.set(0);
            rttCount.set(0);

            ObservationData data = ObservationData.builder()
                    .timestamp(currentTime)
                    .bytesPerSecond(bytesPerSecond)
                    .bandwidthUtilization((bytesPerSecond * 100) / control.maxGlobalBandwidth)
                    .dropRate(dropRate)
                    .avgRtt(avgRtt)
                    .activeConnections(control.connectionControllers.size())
                    .activeIps(control.ipControllers.size())
                    .build();

            observations.offerLast(data);
            while (observations.size() > MAX_OBSERVATIONS) {
                observations.pollFirst();
            }

            // 检测连续丢包
            if (dropRate > 5.0) {
                consecutiveDrops.incrementAndGet();
            } else {
                consecutiveDrops.set(0);
            }
        }

        public void recordDrop(String type) {
            // 记录丢包事件
        }

        public void recordRtt(long rtt) {
            lastRtt.addAndGet(rtt);
            rttCount.incrementAndGet();
        }

        public ObservationData getLatestObservations() {
            ObservationData latest = observations.peekLast();
            if (latest == null) return null;

            long totalBandwidth = 0;
            int count = 0;

            for (ObservationData data : observations) {
                if (count >= 10) break;
                totalBandwidth += data.getBandwidthUtilization();
                count++;
            }

            if (count == 0) return latest;

            return ObservationData.builder()
                    .timestamp(latest.getTimestamp())
                    .bytesPerSecond(latest.getBytesPerSecond())
                    .bandwidthUtilization(totalBandwidth / count)
                    .dropRate(latest.getDropRate())
                    .avgRtt(latest.getAvgRtt())
                    .activeConnections(latest.getActiveConnections())
                    .activeIps(latest.getActiveIps())
                    .sampleCount(count)
                    .consecutiveDrops(consecutiveDrops.get())
                    .build();
        }

        public long getConsecutiveDrops() {
            return consecutiveDrops.get();
        }
    }

    /**
     * 平滑调节器 - 避免频繁抖动
     */
    private static class SmoothTuner {
        private final AtomicReference<NetworkState.State> lastState = 
            new AtomicReference<>(NetworkState.State.NORMAL);
        private volatile int sameStateCount = 0;
        private static final int STATE_CONFIRMATION_THRESHOLD = 3; // 需要连续3次相同状态才调节
        private final AtomicReference<TuningStrategy> lastStrategy = new AtomicReference<>();

        public void performSmoothTuning(IntelligentGlobalFlowControl control) {
            ObservationData observations = control.observationSystem.getLatestObservations();
            if (observations == null || observations.getSampleCount() == 0) return;

            // 1. 分析网络状态
            NetworkState state = analyzeNetworkState(observations);

            // 2. 平滑机制：只有连续相同状态达到阈值才调节
            NetworkState.State last = lastState.get();
            if (state.getState() == last) {
                sameStateCount++;
            } else {
                sameStateCount = 1;
                lastState.set(state.getState());
            }

            if (sameStateCount < STATE_CONFIRMATION_THRESHOLD) {
                log.debug("[平滑调节] 状态未稳定 ({}次{}/{}), 暂不调节", 
                    sameStateCount, state.getState(), STATE_CONFIRMATION_THRESHOLD);
                return;
            }

            // 3. 创建调节策略（带平滑）
            TuningStrategy strategy = createSmoothStrategy(state, lastStrategy.get());

            // 4. 执行调节
            executeTuning(control, strategy);

            // 5. 记录调节
            lastStrategy.set(strategy);
            logTuningDecision(state, strategy);
        }

        private NetworkState analyzeNetworkState(ObservationData observations) {
            long utilization = observations.getBandwidthUtilization();
            double dropRate = observations.getDropRate();
            long consecutiveDrops = observations.getConsecutiveDrops();

            NetworkState.State state;
            if (utilization < 60) {
                state = NetworkState.State.UNDERUTILIZED;
            } else if (utilization > 95 || dropRate > 5.0 || consecutiveDrops > 3) {
                state = NetworkState.State.CONGESTED;
            } else if (utilization >= 85 && dropRate < 2.0) {
                state = NetworkState.State.OPTIMAL;
            } else {
                state = NetworkState.State.NORMAL;
            }

            return NetworkState.builder()
                    .state(state)
                    .utilization(utilization)
                    .dropRate(dropRate)
                    .consecutiveDrops(consecutiveDrops)
                    .build();
        }

        private TuningStrategy createSmoothStrategy(NetworkState state, TuningStrategy lastStrategy) {
            double globalAdjust = 1.0;
            double connectionAdjust = 1.0;
            String reason = "";

            switch (state.getState()) {
                case UNDERUTILIZED:
                    globalAdjust = 1.15;  // 温和提升15%
                    connectionAdjust = 1.1;
                    reason = "带宽利用率低，温和提速";
                    break;
                case OPTIMAL:
                    globalAdjust = 1.0;   // 保持
                    connectionAdjust = 1.0;
                    reason = "状态最优，保持稳定";
                    break;
                case CONGESTED:
                    globalAdjust = 0.85;  // 积极降速15%
                    connectionAdjust = 0.9;
                    reason = "检测到拥塞，积极降速";
                    break;
                case NORMAL:
                    globalAdjust = 1.05;  // 微调5%
                    connectionAdjust = 1.05;
                    reason = "正常状态，微调";
                    break;
            }

            // 平滑限制：单次调节不超过上次策略的20%
            if (lastStrategy != null) {
                double lastGlobal = lastStrategy.getGlobalRateAdjustment();
                double maxDelta = Math.abs(lastGlobal - 1.0) * 0.2;
                if (Math.abs(globalAdjust - 1.0) > maxDelta + 0.01) {
                    globalAdjust = globalAdjust > 1.0 ? 1.0 + maxDelta : 1.0 - maxDelta;
                    reason += " (平滑限制)";
                }
            }

            return TuningStrategy.builder()
                    .globalRateAdjustment(globalAdjust)
                    .connectionRateAdjustment(connectionAdjust)
                    .reason(reason)
                    .build();
        }

        private void executeTuning(IntelligentGlobalFlowControl control, TuningStrategy strategy) {
            long currentGlobalRate = control.globalController.getSendRate();
            long newGlobalRate = (long) (currentGlobalRate * strategy.getGlobalRateAdjustment());
            newGlobalRate = Math.max(10 * 1024 * 1024, 
                Math.min(newGlobalRate, control.maxGlobalBandwidth));
            control.globalController.updateSendRate(newGlobalRate);

            control.connectionControllers.forEach((connId, controller) -> {
                long currentRate = controller.getSendRate();
                long newRate = (long) (currentRate * strategy.getConnectionRateAdjustment());
                newRate = Math.max(control.minConnectionBandwidth, 
                    Math.min(newRate, control.maxConnectionBandwidth));
                controller.updateSendRate(newRate);
            });

            control.updateUtilization();
        }

        private void logTuningDecision(NetworkState state, TuningStrategy strategy) {
            log.info("[平滑调节] 状态:{} (连续{}次) | 利用率:{}% | 丢包率:{}% | 策略:{} | 全局:{}x | 连接:{}x",
                    state.getState(), sameStateCount,
                    state.getUtilization(),
                    String.format("%.2f", state.getDropRate()),
                    strategy.getReason(),
                    String.format("%.2f", strategy.getGlobalRateAdjustment()),
                    String.format("%.2f", strategy.getConnectionRateAdjustment()));
        }
    }

    /**
     * 自我恢复系统 - 检测并恢复竞争卡住
     */
    private static class SelfRecoverySystem {
        private volatile long lastActiveTime = System.currentTimeMillis();
        private volatile long lastSentCheck = 0;
        private volatile int stuckCounter = 0;
        private static final int STUCK_THRESHOLD = 3;
        private static final long STUCK_TIMEOUT_MS = 5000; // 5秒无发送认为卡住

        public void checkAndRecover(IntelligentGlobalFlowControl control) {
            long currentTime = System.currentTimeMillis();
            long currentSent = control.totalBytesSent.get();

            // 检查是否有新数据发送
            if (currentSent > lastSentCheck) {
                lastSentCheck = currentSent;
                lastActiveTime = currentTime;
                stuckCounter = 0;
                return; // 正常
            }

            // 检查是否超时
            long idleTime = currentTime - lastActiveTime;
            if (idleTime < STUCK_TIMEOUT_MS) {
                return; // 还未超时
            }

            // 检测到可能卡住
            stuckCounter++;
            log.warn("[自我恢复] 检测到可能卡住 ({}/{}), 空闲时间:{}ms, 活跃连接:{}",
                    stuckCounter, STUCK_THRESHOLD, idleTime, 
                    control.connectionControllers.size());

            if (stuckCounter >= STUCK_THRESHOLD) {
                performRecovery(control);
                stuckCounter = 0;
                lastActiveTime = currentTime;
            }
        }

        private void performRecovery(IntelligentGlobalFlowControl control) {
            log.error("[自我恢复] 触发自我恢复机制!");

            // 1. 重置令牌桶
            control.globalController.reset();

            // 2. 重置所有连接控制器
            control.connectionControllers.forEach((connId, controller) -> {
                controller.reset();
                log.info("[自我恢复] 重置连接: {}", connId);
            });

            // 3. 重置IP控制器
            control.ipControllers.forEach((ip, controller) -> {
                controller.reset();
            });

            // 4. 降低初始速率，避免再次卡住
            control.globalController.updateSendRate(control.maxGlobalBandwidth / 4);

            log.warn("[自我恢复] 恢复完成，速率已降低到安全水平");
        }
    }

    // ========== 数据类 ==========

    public static class OptimizedFlowControlResult {
        private final boolean allowed;
        private final String reason;

        private OptimizedFlowControlResult(boolean allowed, String reason) {
            this.allowed = allowed;
            this.reason = reason;
        }

        public static OptimizedFlowControlResult allowed() {
            return new OptimizedFlowControlResult(true, "允许");
        }

        public static OptimizedFlowControlResult rejected(String reason) {
            return new OptimizedFlowControlResult(false, reason);
        }

        public boolean isAllowed() { return allowed; }
        public String getReason() { return reason; }
    }

    @lombok.Builder
    @lombok.Data
    public static class OptimizedGlobalStats {
        private long totalBytesSent;
        private long totalBytesReceived;
        private long droppedBytes;
        private long currentGlobalRate;
        private long maxGlobalRate;
        private long bandwidthUtilization;
        private int activeConnections;
        private int activeIps;
        private AdvancedFlowStats globalStats;
    }

    @lombok.Builder
    @lombok.Data
    public static class ObservationData {
        private long timestamp;
        private long bytesPerSecond;
        private long bandwidthUtilization;
        private double dropRate;
        private long avgRtt;
        private int activeConnections;
        private int activeIps;
        @lombok.Builder.Default
        private int sampleCount = 1;
        @lombok.Builder.Default
        private long consecutiveDrops = 0;
    }

    @lombok.Builder
    @lombok.Data
    public static class NetworkState {
        public enum State {
            UNDERUTILIZED, OPTIMAL, NORMAL, CONGESTED
        }
        private State state;
        private long utilization;
        private double dropRate;
        private long consecutiveDrops;
    }

    @lombok.Builder
    @lombok.Data
    public static class TuningStrategy {
        private double globalRateAdjustment;
        private double connectionRateAdjustment;
        private String reason;
    }
}
