package com.bit.solana.p2p.quic.control;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * QUIC MTU探测和发现机制
 * 实现动态MTU大小探测和优化
 */
@Slf4j
@Data
public class MtuDiscovery {
    // 连接ID
    private final long connectionId;
    
    // MTU探测相关常量
    private static final int MIN_MTU = 1200;          // IPv6最小MTU
    private static final int MAX_MTU = 1500;          // 以太网标准MTU
    private static final int INITIAL_MTU = 1400;       // 初始MTU
    private static final int MTU_STEP_SIZE = 50;       // MTU探测步长
    private static final int MAX_RETRIES = 3;          // 最大重试次数
    private static final long PROBE_TIMEOUT = 5000;     // 探测超时时间 (ms)
    
    // 当前MTU状态
    private final AtomicInteger currentMtu = new AtomicInteger(INITIAL_MTU);
    private final AtomicInteger maxConfirmedMtu = new AtomicInteger(INITIAL_MTU);
    private final AtomicReference<MtuState> state = new AtomicReference<>(MtuState.SEARCHING);
    
    // 探测历史记录
    private final List<MtuProbeResult> probeHistory = new ArrayList<>();
    private final AtomicLong lastProbeTime = new AtomicLong(System.currentTimeMillis());
    private final AtomicInteger probeAttempts = new AtomicInteger(0);
    
    // 统计信息
    private final AtomicInteger successfulProbes = new AtomicInteger(0);
    private final AtomicInteger failedProbes = new AtomicInteger(0);
    private volatile boolean discoveryComplete = false;
    
    /**
     * MTU状态枚举
     */
    public enum MtuState {
        SEARCHING,      // 搜索阶段
        TESTING,        // 测试阶段
        CONFIRMED,      // 已确认
        OPTIMIZED       // 已优化
    }
    
    /**
     * MTU探测结果
     */
    @Data
    public static class MtuProbeResult {
        private final int mtuSize;
        private final boolean successful;
        private final long probeTime;
        private final long responseTime;
        private final String errorMessage;
        
        public MtuProbeResult(int mtuSize, boolean successful, long probeTime, long responseTime, String errorMessage) {
            this.mtuSize = mtuSize;
            this.successful = successful;
            this.probeTime = probeTime;
            this.responseTime = responseTime;
            this.errorMessage = errorMessage;
        }
        
        public boolean isSuccessful() {
            return successful && errorMessage == null;
        }
    }
    
    /**
     * 构造函数
     */
    public MtuDiscovery(long connectionId) {
        this.connectionId = connectionId;
        log.info("MTU探测初始化: connectionId={}, initialMtu={}", connectionId, INITIAL_MTU);
    }
    
    /**
     * 开始MTU探测
     */
    public void startDiscovery() {
        log.info("开始MTU探测: connectionId={}", connectionId);
        state.set(MtuState.SEARCHING);
        discoveryComplete = false;
        probeHistory.clear();
        
        // 从当前MTU开始探测
        probeMtu(currentMtu.get());
    }
    
    /**
     * 探测指定大小的MTU
     */
    public boolean probeMtu(int mtuSize) {
        if (!isValidMtu(mtuSize)) {
            log.warn("无效的MTU大小: connectionId={}, mtuSize={}", connectionId, mtuSize);
            return false;
        }
        
        state.set(MtuState.TESTING);
        lastProbeTime.set(System.currentTimeMillis());
        probeAttempts.incrementAndGet();
        
        log.debug("探测MTU: connectionId={}, mtuSize={}, attempt={}", connectionId, mtuSize, probeAttempts.get());
        
        // 模拟MTU探测过程
        long startTime = System.currentTimeMillis();
        boolean success = simulateMtuProbe(mtuSize);
        long responseTime = System.currentTimeMillis() - startTime;
        
        MtuProbeResult result = new MtuProbeResult(mtuSize, success, startTime, responseTime, null);
        probeHistory.add(result);
        
        if (success) {
            successfulProbes.incrementAndGet();
            onProbeSuccess(mtuSize);
        } else {
            failedProbes.incrementAndGet();
            onProbeFailure(mtuSize);
        }
        
        return success;
    }
    
    /**
     * 模拟MTU探测（实际应用中这里应该是网络探测）
     */
    private boolean simulateMtuProbe(int mtuSize) {
        // 简单的模拟：大于1400的MTU有一定概率失败
        if (mtuSize > 1450) {
            return Math.random() > 0.3; // 30%失败率
        } else if (mtuSize > 1400) {
            return Math.random() > 0.1; // 10%失败率
        }
        return true; // 1400以下基本成功
    }
    
    /**
     * 探测成功处理
     */
    private void onProbeSuccess(int mtuSize) {
        log.info("MTU探测成功: connectionId={}, mtuSize={}", connectionId, mtuSize);
        
        // 更新当前MTU和最大确认MTU
        currentMtu.set(mtuSize);
        maxConfirmedMtu.set(mtuSize);
        
        // 尝试探测更大的MTU
        if (mtuSize < MAX_MTU && !discoveryComplete) {
            int nextMtu = Math.min(mtuSize + MTU_STEP_SIZE, MAX_MTU);
            if (probeAttempts.get() < 10) { // 限制探测次数
                scheduleProbe(nextMtu);
            } else {
                completeDiscovery();
            }
        } else {
            completeDiscovery();
        }
    }
    
    /**
     * 探测失败处理
     */
    private void onProbeFailure(int mtuSize) {
        log.warn("MTU探测失败: connectionId={}, mtuSize={}", connectionId, mtuSize);
        
        if (probeAttempts.get() >= MAX_RETRIES) {
            // 达到最大重试次数，确认当前MTU
            log.info("达到最大重试次数，确认MTU: connectionId={}, mtuSize={}", 
                     connectionId, maxConfirmedMtu.get());
            completeDiscovery();
        } else {
            // 重新测试当前MTU
            scheduleProbe(currentMtu.get());
        }
    }
    
    /**
     * 调度下一次探测
     */
    private void scheduleProbe(int mtuSize) {
        // 在实际应用中，这里应该延迟执行
        new Thread(() -> {
            try {
                Thread.sleep(100); // 100ms延迟
                probeMtu(mtuSize);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }
    
    /**
     * 完成MTU发现
     */
    private void completeDiscovery() {
        discoveryComplete = true;
        state.set(MtuState.CONFIRMED);
        log.info("MTU发现完成: connectionId={}, finalMtu={}, probeAttempts={}", 
                 connectionId, maxConfirmedMtu.get(), probeAttempts.get());
        
        // 尝试进一步优化
        optimizeMtu();
    }
    
    /**
     * MTU优化
     */
    private void optimizeMtu() {
        state.set(MtuState.OPTIMIZED);
        
        // 基于探测历史数据进行优化
        if (probeHistory.size() >= 3) {
            // 分析最近的探测结果
            double recentSuccessRate = getRecentSuccessRate();
            if (recentSuccessRate > 0.8 && maxConfirmedMtu.get() < MAX_MTU) {
                // 如果最近成功率很高，尝试稍微增加MTU
                int optimizedMtu = Math.min(maxConfirmedMtu.get() + 10, MAX_MTU);
                log.info("MTU优化: connectionId={}, originalMtu={}, optimizedMtu={}", 
                         connectionId, maxConfirmedMtu.get(), optimizedMtu);
                currentMtu.set(optimizedMtu);
            }
        }
    }
    
    /**
     * 验证MTU是否有效
     */
    private boolean isValidMtu(int mtu) {
        return mtu >= MIN_MTU && mtu <= MAX_MTU;
    }
    
    /**
     * 获取最近的成功率
     */
    private double getRecentSuccessRate() {
        List<MtuProbeResult> recentProbes = probeHistory.subList(
            Math.max(0, probeHistory.size() - 5), 
            probeHistory.size()
        );
        
        long successCount = recentProbes.stream()
            .mapToLong(p -> p.isSuccessful() ? 1 : 0)
            .sum();
        
        return recentProbes.isEmpty() ? 0.0 : (double) successCount / recentProbes.size();
    }
    
    /**
     * 获取推荐的MTU大小
     */
    public int getRecommendedMtu() {
        return maxConfirmedMtu.get();
    }
    
    /**
     * 获取当前MTU大小
     */
    public int getCurrentMtu() {
        return currentMtu.get();
    }
    
    /**
     * 获取MTU发现状态
     */
    public MtuState getState() {
        return state.get();
    }
    
    /**
     * 检查MTU发现是否完成
     */
    public boolean isDiscoveryComplete() {
        return discoveryComplete;
    }
    
    /**
     * 获取探测成功率
     */
    public double getSuccessRate() {
        int total = successfulProbes.get() + failedProbes.get();
        return total == 0 ? 0.0 : (double) successfulProbes.get() / total;
    }
    
    /**
     * 获取探测统计信息
     */
    public String getStats() {
        return String.format(
            "MtuDiscovery{connectionId=%d, currentMtu=%d, maxConfirmedMtu=%d, " +
            "state=%s, complete=%s, successRate=%.2f%%, probes=%d, " +
            "successful=%d, failed=%d}",
            connectionId, currentMtu.get(), maxConfirmedMtu.get(),
            state.get(), discoveryComplete, getSuccessRate() * 100,
            probeAttempts.get(), successfulProbes.get(), failedProbes.get()
        );
    }
    
    /**
     * 重置MTU发现
     */
    public void reset() {
        currentMtu.set(INITIAL_MTU);
        maxConfirmedMtu.set(INITIAL_MTU);
        state.set(MtuState.SEARCHING);
        discoveryComplete = false;
        probeHistory.clear();
        lastProbeTime.set(System.currentTimeMillis());
        probeAttempts.set(0);
        successfulProbes.set(0);
        failedProbes.set(0);
        
        log.info("MTU发现重置: connectionId={}", connectionId);
    }
    
    /**
     * 获取探测历史
     */
    public List<MtuProbeResult> getProbeHistory() {
        return new ArrayList<>(probeHistory);
    }
}