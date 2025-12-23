package com.bit.solana.p2p.quic.control;

import lombok.extern.slf4j.Slf4j;

/**
 * CUBIC拥塞控制算法实现
 * 适用于高带宽、高延迟网络环境
 */
@Slf4j
public class CubicCongestionControl implements CongestionControl {
    
    // CUBIC参数
    private static final double BETA = 0.7;          // 乘法减小因子
    private static final double C = 0.4;            // CUBIC缩放因子
    private static final long MIN_CWND = 2 * 1024;   // 最小拥塞窗口 2KB
    private static final long MAX_CWND = 100 * 1024 * 1024; // 最大拥塞窗口 100MB
    
    // CUBIC状态变量
    private long wMax;                  // 上次拥塞时的窗口大小
    private long lastMaxWindow;          // 上次最大窗口
    private long cubicStartTime;          // CUBIC函数起始时间
    private long originPoint;             // 拥塞起始点
    
    // 拥塞控制状态
    private final CongestionState state;
    
    public CubicCongestionControl() {
        this.state = new CongestionState();
        this.cubicStartTime = System.currentTimeMillis();
    }
    
    @Override
    public void onAck(long ackedBytes, long rtt) {
        synchronized (this) {
            state.updateRtt(rtt);
            state.setTotalAckedBytes(state.getTotalAckedBytes() + ackedBytes);
            state.setPacketsSent(state.getPacketsSent() + 1);
            
            updateSendRate();
            
            if (state.isInSlowStart()) {
                handleSlowStart();
            } else if (state.isInCongestionAvoidance()) {
                handleCongestionAvoidance();
            }
            
            log.debug("[CUBIC ACK] 确认字节:{}, RTT:{}, 当前窗口:{}, 发送速率:{}", 
                    ackedBytes, rtt, getCongestionWindow(), getSendRate());
        }
    }
    
    @Override
    public void onPacketLoss(long lostBytes) {
        synchronized (this) {
            state.setTotalLostBytes(state.getTotalLostBytes() + lostBytes);
            state.setPacketsLost(state.getPacketsLost() + 1);
            
            // 乘法减小
            long currentCwnd = state.getCongestionWindow();
            state.setSlowStartThreshold(currentCwnd * (long) (BETA * 1000) / 1000);
            state.setCongestionWindow(Math.max(MIN_CWND, state.getSlowStartThreshold()));
            
            // 更新CUBIC状态
            wMax = currentCwnd;
            lastMaxWindow = currentCwnd;
            cubicStartTime = System.currentTimeMillis();
            originPoint = currentCwnd;
            
            state.setInRecovery(true);
            
            log.warn("[CUBIC丢包] 丢失字节:{}, 新窗口:{}, 阈值:{}", 
                    lostBytes, state.getCongestionWindow(), state.getSlowStartThreshold());
        }
    }
    
    @Override
    public long getCongestionWindow() {
        return state.getCongestionWindow();
    }
    
    @Override
    public long getSendRate() {
        return state.getCurrentSendRate();
    }
    
    @Override
    public void setMaxSendRate(long maxRate) {
        state.setMaxSendRate(maxRate);
    }
    
    @Override
    public boolean canSend(long dataSize) {
        synchronized (this) {
            // 检查拥塞窗口
            if (dataSize > state.getCongestionWindow()) {
                return false;
            }
            
            // 检查发送速率
            updateSendRate();
            return dataSize <= state.getCurrentSendRate() / 1000; // 每毫秒的限制
        }
    }
    
    @Override
    public void updateSendRate() {
        long currentTime = System.currentTimeMillis();
        long timeDiff = currentTime - state.getLastUpdateTime();
        
        if (timeDiff >= 10) { // 每10ms更新一次
            // 防止RTT为0导致除零错误
            long rtt = Math.max(1, state.getSmoothedRtt());
            long targetRate = Math.min(state.getCongestionWindow() * 8 / rtt, 
                                   state.getMaxSendRate());
            
            // 平滑调整发送速率
            long rateAdjustment = (targetRate - state.getCurrentSendRate()) / 4;
            state.setCurrentSendRate(Math.max(1024, 
                                          Math.min(targetRate + rateAdjustment, state.getMaxSendRate())));
            
            state.setLastUpdateTime(currentTime);
        }
    }
    
    @Override
    public void reset() {
        synchronized (this) {
            state.resetStats();
            state.setCongestionWindow(10 * 1024);
            state.setSlowStartThreshold(Long.MAX_VALUE);
            state.setInSlowStart(true);
            state.setInCongestionAvoidance(false);
            state.setInRecovery(false);
            
            wMax = 0;
            lastMaxWindow = 0;
            cubicStartTime = System.currentTimeMillis();
            originPoint = 0;
        }
    }
    
    @Override
    public CongestionState getState() {
        return state;
    }
    
    /**
     * 处理慢启动阶段
     */
    private void handleSlowStart() {
        long cwnd = state.getCongestionWindow();
        
        // 每个ACK增加一个MSS（假设最大段大小为1460字节）
        cwnd += 1460;
        
        // 检查是否超过慢启动阈值
        if (cwnd >= state.getSlowStartThreshold()) {
            state.setInSlowStart(false);
            state.setInCongestionAvoidance(true);
            cubicStartTime = System.currentTimeMillis();
        }
        
        state.setCongestionWindow(Math.min(cwnd, MAX_CWND));
    }
    
    /**
     * 处理拥塞避免阶段（CUBIC核心算法）
     */
    private void handleCongestionAvoidance() {
        long currentTime = System.currentTimeMillis();
        long timeSinceLastLoss = currentTime - cubicStartTime;
        
        // CUBIC函数计算
        double t = timeSinceLastLoss / 1000.0; // 转换为秒
        double k = Math.cbrt(wMax * (1 - BETA) / C);
        
        // C(t) = C * (t - K)^3 + w_max
        double cubicTarget = C * Math.pow(t - k, 3) + wMax;
        
        // 更新拥塞窗口
        long targetCwnd = (long) Math.max(MIN_CWND, 
                                        Math.min(cubicTarget, MAX_CWND));
        
        state.setCongestionWindow(targetCwnd);
        
        // RTT公平性调整
        if (state.getSmoothedRtt() > 0) {
            long rttFair = state.getCongestionWindow() * 1000 / state.getSmoothedRtt();
            if (rttFair < state.getCurrentSendRate()) {
                // 适当增加窗口以保持公平性
                state.setCongestionWindow(targetCwnd + 1460);
            }
        }
    }
    
    /**
     * 检查恢复阶段是否结束
     */
    public boolean checkRecoveryEnd() {
        if (!state.isInRecovery()) {
            return false;
        }
        
        // 恢复一个RTT时间后结束恢复阶段
        long recoveryTime = System.currentTimeMillis() - state.getLastUpdateTime();
        if (recoveryTime > state.getSmoothedRtt()) {
            state.setInRecovery(false);
            state.setInCongestionAvoidance(true);
            cubicStartTime = System.currentTimeMillis();
            return true;
        }
        
        return false;
    }
}