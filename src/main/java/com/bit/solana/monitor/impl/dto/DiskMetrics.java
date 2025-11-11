package com.bit.solana.monitor.impl.dto;

import lombok.Data;

/**
 * 磁盘监控数据
 */
@Data
public class DiskMetrics {
    private String device; // 磁盘设备名
    private long totalSpace; // 总容量(字节)
    private long freeSpace; // 空闲容量(字节)
    private long usedSpace; // 已使用容量(字节)
    private double usedPercent; // 使用率(%)
}