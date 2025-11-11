package com.bit.solana.monitor.impl.dto;

import lombok.Data;

/**
 * CPU监控数据
 */
@Data
public class CpuMetrics {
    private double systemCpuUsage; // 系统CPU使用率(%)
    private double processCpuUsage; // 当前进程CPU使用率(%)
    private int logicalCores; // 逻辑核心数
    private int physicalCores; // 物理核心数
}