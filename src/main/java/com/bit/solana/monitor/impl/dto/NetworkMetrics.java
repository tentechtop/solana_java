package com.bit.solana.monitor.impl.dto;

import lombok.Data;

/**
 * 网络监控数据
 */
@Data
public class NetworkMetrics {
    private String interfaceName; // 网卡名称
    private long receivedBytes; // 接收字节数
    private long sentBytes; // 发送字节数
    private long receivedPackets; // 接收数据包数
    private long sentPackets; // 发送数据包数
    private double receiveRate; // 接收速率(B/s)
    private double sendRate; // 发送速率(B/s)
}