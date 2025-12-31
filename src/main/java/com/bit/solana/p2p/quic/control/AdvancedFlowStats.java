package com.bit.solana.p2p.quic.control;

public class AdvancedFlowStats {
    private long totalBytesSent;
    private long totalBytesDropped;
    private long adaptationCount;
    private long currentSendRate;
    private long maxSendRate;
    private long minSendRate;
    private long availableTokens;
    private long maxTokens;
    private long currentRtt;
    private long minRtt;
    private long deliveryRate;
    private long maxDeliveryRate;
    private int congestionState;  // 0=启动, 1=探测, 2=传输, 3=拥塞
    private boolean fastPathEnabled;
    private long consecutiveSuccess;
    private long consecutiveFailures;
    private long optimalSendWindow;

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final AdvancedFlowStats stats = new AdvancedFlowStats();

        public Builder totalBytesSent(long v) { stats.totalBytesSent = v; return this; }
        public Builder totalBytesDropped(long v) { stats.totalBytesDropped = v; return this; }
        public Builder adaptationCount(long v) { stats.adaptationCount = v; return this; }
        public Builder currentSendRate(long v) { stats.currentSendRate = v; return this; }
        public Builder maxSendRate(long v) { stats.maxSendRate = v; return this; }
        public Builder minSendRate(long v) { stats.minSendRate = v; return this; }
        public Builder availableTokens(long v) { stats.availableTokens = v; return this; }
        public Builder maxTokens(long v) { stats.maxTokens = v; return this; }
        public Builder currentRtt(long v) { stats.currentRtt = v; return this; }
        public Builder minRtt(long v) { stats.minRtt = v; return this; }
        public Builder deliveryRate(long v) { stats.deliveryRate = v; return this; }
        public Builder maxDeliveryRate(long v) { stats.maxDeliveryRate = v; return this; }
        public Builder congestionState(int v) { stats.congestionState = v; return this; }
        public Builder fastPathEnabled(boolean v) { stats.fastPathEnabled = v; return this; }
        public Builder consecutiveSuccess(long v) { stats.consecutiveSuccess = v; return this; }
        public Builder consecutiveFailures(long v) { stats.consecutiveFailures = v; return this; }
        public Builder optimalSendWindow(long v) { stats.optimalSendWindow = v; return this; }

        public AdvancedFlowStats build() { return stats; }
    }

    // Getters
    public long getTotalBytesSent() { return totalBytesSent; }
    public long getTotalBytesDropped() { return totalBytesDropped; }
    public long getAdaptationCount() { return adaptationCount; }
    public long getCurrentSendRate() { return currentSendRate; }
    public long getMaxSendRate() { return maxSendRate; }
    public long getMinSendRate() { return minSendRate; }
    public long getAvailableTokens() { return availableTokens; }
    public long getMaxTokens() { return maxTokens; }
    public long getCurrentRtt() { return currentRtt; }
    public long getMinRtt() { return minRtt; }
    public long getDeliveryRate() { return deliveryRate; }
    public long getMaxDeliveryRate() { return maxDeliveryRate; }
    public int getCongestionState() { return congestionState; }
    public boolean isFastPathEnabled() { return fastPathEnabled; }
    public long getConsecutiveSuccess() { return consecutiveSuccess; }
    public long getConsecutiveFailures() { return consecutiveFailures; }
    public long getOptimalSendWindow() { return optimalSendWindow; }

    private String getCongestionStateName() {
        switch (congestionState) {
            case 0: return "启动";
            case 1: return "探测";
            case 2: return "传输";
            case 3: return "拥塞";
            default: return "未知";
        }
    }

    @Override
    public String toString() {
        return String.format(
                "AdvancedFlowStats{\n" +
                "  发送: %.2f MB, 丢弃: %.2f MB\n" +
                "  速率: %.2f / %.2f / %.2f MB/s (当前/最大/最小)\n" +
                "  令牌: %d / %d (%.1f%%)\n" +
                "  RTT: %d / %d ms (当前/最小)\n" +
                "  传输速率: %.2f MB/s (最大: %.2f MB/s)\n" +
                "  拥塞状态: %s\n" +
                "  快速路径: %s (成功:%d, 失败:%d)\n" +
                "  自适应调整: %d 次\n" +
                "  最优窗口: %.2f MB\n" +
                "}",
                totalBytesSent / 1024.0 / 1024.0,
                totalBytesDropped / 1024.0 / 1024.0,
                currentSendRate / 1024.0 / 1024.0,
                maxSendRate / 1024.0 / 1024.0,
                minSendRate / 1024.0 / 1024.0,
                availableTokens, maxTokens, (double)availableTokens / maxTokens * 100,
                currentRtt, minRtt,
                deliveryRate / 1024.0 / 1024.0,
                maxDeliveryRate / 1024.0 / 1024.0,
                getCongestionStateName(),
                fastPathEnabled, consecutiveSuccess, consecutiveFailures,
                adaptationCount,
                optimalSendWindow / 1024.0 / 1024.0);
    }
}
