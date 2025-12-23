package com.bit.solana.p2p.quic.control;

import lombok.Builder;
import lombok.Data;

/**
 * 流量控制统计信息
 */
@Data
@Builder
public class FlowControlStats {
    
    // 发送统计
    private long totalBytesSent;         // 总发送字节数
    private long totalBytesDropped;       // 总丢弃字节数
    
    // 速率信息
    private long currentSendRate;         // 当前发送速率（字节/秒）
    private long maxSendRate;            // 最大发送速率（字节/秒）
    
    // 令牌桶状态
    private long availableTokens;         // 可用令牌数
    private double tokenUtilization;      // 令牌桶利用率（0-1）
    private long maxTokens;              // 令牌桶最大容量
    
    // 异常统计
    private long tokensExhaustedCount;    // 令牌耗尽次数
    
    /**
     * 获取丢包率
     */
    public double getDropRate() {
        long total = totalBytesSent + totalBytesDropped;
        return total == 0 ? 0.0 : (double) totalBytesDropped / total;
    }
    
    /**
     * 获取当前速率使用率（0-1）
     */
    public double getRateUtilization() {
        return maxSendRate == 0 ? 0.0 : (double) currentSendRate / maxSendRate;
    }
    
    /**
     * 格式化统计信息
     */
    public String formatStats() {
        return String.format(
            "流量控制统计 - 发送:%sB, 丢弃:%sB, 速率:%sB/s, 利用率:%.1f%%, 令牌:%s/%s(%.1f%%)",
            formatBytes(totalBytesSent),
            formatBytes(totalBytesDropped),
            formatBytes(currentSendRate),
            getRateUtilization() * 100,
            formatBytes(availableTokens),
            formatBytes(maxTokens),
            tokenUtilization * 100
        );
    }
    
    private String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + "B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.1fKB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.1fMB", bytes / (1024.0 * 1024));
        } else {
            return String.format("%.1fGB", bytes / (1024.0 * 1024 * 1024));
        }
    }
}