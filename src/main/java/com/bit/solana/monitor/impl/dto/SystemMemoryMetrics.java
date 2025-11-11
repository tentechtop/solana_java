package com.bit.solana.monitor.impl.dto;

import lombok.Data;

/**
 * 系统内存监控数据
 */
@Data
public class SystemMemoryMetrics {
    private long total; // 总物理内存(字节)
    private long available; // 可用内存(字节)
    private long used; // 已使用内存(字节)
    private double usedPercent; // 使用率(%)
}