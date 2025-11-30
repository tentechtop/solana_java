package com.bit.solana.p2p.quic;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 拥塞控制（TCP Reno版：慢启动、拥塞避免、快速重传）
 */
public class CongestionControl {
    private int cwnd = QuicConstants.INITIAL_CWND; // 拥塞窗口
    private int ssthresh = QuicConstants.SSTHRESH_INIT; // 慢启动阈值
    private final AtomicLong duplicateAckCount = new AtomicLong(0); // 重复ACK计数


}
