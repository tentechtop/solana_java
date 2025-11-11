package com.bit.solana.monitor.impl.dto;

import lombok.Data;

/**
 * JVM内存监控数据
 */
@Data
public class JvmMemoryMetrics {
    // 堆内存(字节)
    private long heapInit;
    private long heapUsed;
    private long heapCommitted;
    private long heapMax;
    // 非堆内存(字节)
    private long nonHeapInit;
    private long nonHeapUsed;
    private long nonHeapCommitted;
    private long nonHeapMax;
}