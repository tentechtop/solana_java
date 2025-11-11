package com.bit.solana.monitor;


import com.bit.solana.monitor.impl.dto.*;

import java.util.List;

/**
 * 系统监控接口
 * 定义CPU、内存、磁盘、网络的监控能力
 */
public interface SystemMonitor {

    /**
     * 获取CPU监控指标
     */
    CpuMetrics getCpuMetrics();

    /**
     * 获取JVM内存监控指标
     */
    JvmMemoryMetrics getJvmMemoryMetrics();

    /**
     * 获取系统物理内存监控指标
     */
    SystemMemoryMetrics getSystemMemoryMetrics();

    /**
     * 获取磁盘监控指标（支持多磁盘）
     */
    List<DiskMetrics> getDiskMetrics();

    /**
     * 获取网络监控指标（支持多网卡）
     */
    List<NetworkMetrics> getNetworkMetrics();
}