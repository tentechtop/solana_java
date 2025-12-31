package com.bit.solana.p2p.quic.control;

public class FlowControlStats {
    private long totalBytesSent;
    private long totalBytesDropped;
    private long tokensExhaustedCount;
    private long currentSendRate;
    private long maxSendRate;
    private long availableTokens;
    private long maxTokens;
    private double tokenUtilization;

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private FlowControlStats stats = new FlowControlStats();

        public Builder totalBytesSent(long v) { stats.totalBytesSent = v; return this; }
        public Builder totalBytesDropped(long v) { stats.totalBytesDropped = v; return this; }
        public Builder tokensExhaustedCount(long v) { stats.tokensExhaustedCount = v; return this; }
        public Builder currentSendRate(long v) { stats.currentSendRate = v; return this; }
        public Builder maxSendRate(long v) { stats.maxSendRate = v; return this; }
        public Builder availableTokens(long v) { stats.availableTokens = v; return this; }
        public Builder maxTokens(long v) { stats.maxTokens = v; return this; }
        public Builder tokenUtilization(double v) { stats.tokenUtilization = v; return this; }

        public FlowControlStats build() { return stats; }
    }

    // Getters
    public long getTotalBytesSent() { return totalBytesSent; }
    public long getTotalBytesDropped() { return totalBytesDropped; }
    public long getTokensExhaustedCount() { return tokensExhaustedCount; }
    public long getCurrentSendRate() { return currentSendRate; }
    public long getMaxSendRate() { return maxSendRate; }
    public long getAvailableTokens() { return availableTokens; }
    public long getMaxTokens() { return maxTokens; }
    public double getTokenUtilization() { return tokenUtilization; }

    @Override
    public String toString() {
        return String.format("FlowControlStats{sent=%d, dropped=%d, rate=%d/%d B/s, tokens=%d/%d (%.1f%%)}",
                totalBytesSent, totalBytesDropped, currentSendRate, maxSendRate,
                availableTokens, maxTokens, tokenUtilization * 100);
    }
}
