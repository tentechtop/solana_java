package com.bit.solana.p2p.quic;

import lombok.Data;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 拥塞控制（TCP NewReno版：慢启动、拥塞避免、快速重传） 支持慢启动（Slow Start） 和拥塞避免（Congestion Avoidance） 两种模式，并在丢包时调整窗口
 * 慢启动：拥塞窗口随确认包线性增长（每确认一个包，窗口增加对应包大小），快速利用网络带宽；
 * 拥塞避免：窗口随确认包缓慢增长（按 kMaxDatagramSize * 包大小 / 当前窗口 递增）；
 * 丢包处理：检测到丢包时，拥塞窗口减半（不低于最小值 2400 字节，即 2 * 1200 字节），并进入拥塞避免模式。
 */
@Data
public class CongestionControl {
    private int cwnd = QuicConstants.INITIAL_CWND; // 拥塞窗口
    private int ssthresh = QuicConstants.SSTHRESH_INIT; // 慢启动阈值
    private final AtomicLong duplicateAckCount = new AtomicLong(0); // 重复ACK计数



    public void onAck() {
        if (cwnd < ssthresh) {
            // 慢启动：cwnd += 1
            cwnd++;
        } else {
            // 拥塞避免：cwnd += 1/cwnd
            cwnd += 1 / cwnd;
        }
        duplicateAckCount.set(0);
    }


    /**
     * 超时（重置拥塞窗口）
     */
    public void onTimeout() {
        ssthresh = Math.max(cwnd / 2, 2);
        cwnd = 1;
        duplicateAckCount.set(0);
    }

    public void onLoss(long lostBytes) {

    }

    /**
     * 收到重复ACK（快速重传/恢复）
     */
    public void onDuplicateAck() {
        duplicateAckCount.incrementAndGet();
        if (duplicateAckCount.get() >= 3) {
            // 快速重传：ssthresh = cwnd/2，cwnd = ssthresh + 3
            ssthresh = Math.max(cwnd / 2, 2);
            cwnd = ssthresh + 3;
        }
    }

    public int getAvailableWindow() {
        return 0;
    }



}
