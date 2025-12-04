package com.bit.solana.p2p.quic;

import lombok.Data;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

@Data
public class QuicMetrics {
    // 连接指标
    private final AtomicLong connectionCount = new AtomicLong(0);
    private final AtomicLong connectionMigrationCount = new AtomicLong(0);

    // 流指标
    private final AtomicLong streamCount = new AtomicLong(0);
    private final LongAdder streamSendCount = new LongAdder();

    // 帧指标
    private final LongAdder totalSendCount = new LongAdder();
    private final LongAdder sendFailCount = new LongAdder();
    private final LongAdder retransmitCount = new LongAdder();
    private final LongAdder maxRetransmitCount = new LongAdder();
    private final LongAdder ackCount = new LongAdder();
    private final LongAdder duplicateFrameCount = new LongAdder();
    private final LongAdder outOfOrderFrameCount = new LongAdder();
    private final LongAdder windowOutOfFrameCount = new LongAdder();
    private final LongAdder windowFullCount = new LongAdder();
    private final LongAdder invalidFrameCount = new LongAdder();
    private final LongAdder deliverFrameCount = new LongAdder();
    private final LongAdder deliverBytes = new LongAdder();
    private final LongAdder qpsLimitCount = new LongAdder();
    private final LongAdder fecRecoverCount = new LongAdder();

    // RTT指标
    private final AtomicLong minRtt = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong maxRtt = new AtomicLong(0);
    private final AtomicLong avgRtt = new AtomicLong(0);
    private final LongAdder rttCount = new LongAdder();

    // MTU指标
    private final AtomicLong currentMtu = new AtomicLong(QuicConstants.DEFAULT_MTU);

    // 文件传输指标
    private final LongAdder fileTransferBytes = new LongAdder();

    // ========== 连接指标 ==========
    public void incrementConnectionCount() { connectionCount.incrementAndGet(); }
    public void decrementConnectionCount() { connectionCount.decrementAndGet(); }
    public void recordConnectionMigration(long connectionId) { connectionMigrationCount.incrementAndGet(); }

    // ========== 流指标 ==========
    public void incrementStreamCount() { streamCount.incrementAndGet(); }
    public void decrementStreamCount() { streamCount.decrementAndGet(); }
    public void incrementStreamSendCount(int streamId) { streamSendCount.increment(); }

    // ========== 帧指标 ==========
    public void incrementTotalSendCount() { totalSendCount.increment(); }
    public void incrementSendFailCount() { sendFailCount.increment(); }
    public void incrementRetransmitCount() { retransmitCount.increment(); }
    public void incrementMaxRetransmitCount() { maxRetransmitCount.increment(); }
    public void incrementAckCount() { ackCount.increment(); }
    public void incrementDuplicateFrameCount() { duplicateFrameCount.increment(); }
    public void incrementOutOfOrderFrameCount() { outOfOrderFrameCount.increment(); }
    public void incrementWindowOutOfFrameCount() { windowOutOfFrameCount.increment(); }
    public void incrementWindowFullCount() { windowFullCount.increment(); }
    public void incrementInvalidFrameCount() { invalidFrameCount.increment(); }
    public void incrementDeliverFrameCount() { deliverFrameCount.increment(); }
    public void incrementDeliverBytes(long bytes) { deliverBytes.add(bytes); }
    public void incrementQpsLimitCount() { qpsLimitCount.increment(); }
    public void incrementFecRecoverCount() { fecRecoverCount.increment(); }

    // ========== RTT指标 ==========
    public void updateRtt(long rttSample) {
        minRtt.updateAndGet(v -> Math.min(v, rttSample));
        maxRtt.updateAndGet(v -> Math.max(v, rttSample));
        rttCount.increment();
        avgRtt.set((avgRtt.get() * (rttCount.longValue() - 1) + rttSample) / rttCount.longValue());
    }

    // ========== MTU指标 ==========
    public void updateCurrentMtu(int mtu) { currentMtu.set(mtu); }

    // ========== 文件传输指标 ==========
    public void updateFileTransferBytes(long bytes) { fileTransferBytes.add(bytes); }
}
