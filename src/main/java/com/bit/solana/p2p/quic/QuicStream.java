package com.bit.solana.p2p.quic;

import com.google.common.util.concurrent.RateLimiter;
import io.netty.buffer.ByteBuf;
import io.netty.util.Timeout;
import io.netty.util.TimerTask;
import lombok.Data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 基于连接创建的流  可靠UDP流（独立序列号、滑动窗口、无队头阻塞）
 */
@Data
public class QuicStream {
    private final QuicConnection connection;
    private final int streamId;
    private final byte priority;
    private final RateLimiter qpsLimiter; // QPS限制
    private final ReentrantLock lock = new ReentrantLock();
    private final QuicMetrics metrics;


    // 发送端状态
    private final AtomicLong sendSequence = new AtomicLong(0); // 下一个要发送的序列号
    private final AtomicLong sndUna = new AtomicLong(0); // 未确认的最小序列号
    private final ConcurrentSkipListMap<Long, QuicFrame> sendBuffer = new ConcurrentSkipListMap<>(); // 发送缓冲区（重传队列）
    private int sendWindowSize; // 发送窗口大小
    private long rtt = QuicConstants.RTO_MIN; // 往返时间
    private long rto = QuicConstants.RTO_MIN; // 重传超时
    private final Timeout retransmitTimer; // 重传定时器
    private final Timeout batchAckTimer; // 批量ACK定时器

    // 接收端状态
    private final AtomicLong recvSequence = new AtomicLong(0); // 期望接收的序列号
    private final ConcurrentSkipListMap<Long, QuicFrame> recvBuffer = new ConcurrentSkipListMap<>(); // 乱序缓存
    private int recvWindowSize = QuicConstants.INITIAL_CWND; // 接收窗口大小
    private final List<Long> ackList = new ArrayList<>(); // 批量ACK列表

    // FEC相关
    private final FecEncoder fecEncoder = new FecEncoder();
    private final FecDecoder fecDecoder = new FecDecoder();
    private volatile boolean closed = false;

    public QuicStream(QuicConnection connection, int streamId, byte priority, long qpsLimit, QuicMetrics metrics) {
        this.connection = connection;
        this.streamId = streamId;
        this.priority = priority;
        this.qpsLimiter = qpsLimit > 0 ? RateLimiter.create(qpsLimit) : RateLimiter.create(Double.MAX_VALUE);
        this.metrics = metrics;
        this.sendWindowSize = connection.getCongestionControl().getCwnd();

        // 重传定时器（100ms检查一次）
        this.retransmitTimer = QuicConstants.TIMER.newTimeout(new TimerTask() {
            @Override
            public void run(Timeout timeout) throws Exception {
                if (!closed) {
                    checkRetransmit();
                    QuicConstants.TIMER.newTimeout(this, 100, TimeUnit.MILLISECONDS);
                }
            }
        }, 100, TimeUnit.MILLISECONDS);

        // 批量ACK定时器
        this.batchAckTimer = QuicConstants.TIMER.newTimeout(new TimerTask() {
            @Override
            public void run(Timeout timeout) throws Exception {
                if (!closed && !ackList.isEmpty()) {
                    sendBatchAck();
                    QuicConstants.TIMER.newTimeout(this, QuicConstants.BATCH_ACK_DELAY, TimeUnit.MILLISECONDS);
                }
            }
        }, QuicConstants.BATCH_ACK_DELAY, TimeUnit.MILLISECONDS);
    }


    /**
     * 发送批量ACK
     */
    private void sendBatchAck() {
        lock.lock();
        try {
            if (ackList.isEmpty()) {
                return;
            }

            // 生成批量ACK帧（取最大的确认号）
            long maxAckSeq = Collections.max(ackList);
            QuicFrame ackFrame = QuicFrame.acquire();
            ackFrame.setConnectionId(connection.getConnectionId());
            ackFrame.setStreamId(streamId);
            ackFrame.setFrameType(QuicConstants.FRAME_TYPE_ACK);
            ackFrame.setAckSequence(maxAckSeq);
            ackFrame.setWindowSize(recvWindowSize);
            ackFrame.setRemoteAddress(connection.getRemoteAddress());

            // 发送ACK
            connection.sendFrame(ackFrame);

            // 清空ACK列表
            ackList.clear();
        } finally {
            lock.unlock();
        }
    }


    /**
     * 发送单个ACK（紧急情况）
     */
    private void sendAck(long ackSeq) {
        QuicFrame ackFrame = QuicFrame.acquire();
        ackFrame.setConnectionId(connection.getConnectionId());
        ackFrame.setStreamId(streamId);
        ackFrame.setFrameType(QuicConstants.FRAME_TYPE_ACK);
        ackFrame.setAckSequence(ackSeq);
        ackFrame.setWindowSize(recvWindowSize);
        ackFrame.setRemoteAddress(connection.getRemoteAddress());
        connection.sendFrame(ackFrame);
    }


    /**
     * 检查重传（超时帧重传、拥塞控制）
     */
    private void checkRetransmit() {
        lock.lock();
        try {
            long now = System.currentTimeMillis();
            for (Map.Entry<Long, QuicFrame> entry : sendBuffer.entrySet()) {
                long seq = entry.getKey();
                QuicFrame frame = entry.getValue();

                // 超时检查
                if (now - frame.getSendTime() > rto) {
                    // 最大重传次数检查
                    if (frame.getRetransmitCount() >= QuicConstants.MAX_RETRANSMIT_TIMES) {
                        // 流故障，不扩散到其他流
                        metrics.incrementMaxRetransmitCount();
                        sendBuffer.remove(seq);
                        continue;
                    }

                    // 重传帧
                    frame.incrementRetransmitCount();
                    frame.setSendTime(now);
                    connection.sendFrame(frame);

                    // 拥塞控制（超时触发慢启动）
                    connection.getCongestionControl().onTimeout();
                    sendWindowSize = connection.getCongestionControl().getCwnd();

                    metrics.incrementRetransmitCount();
                }
            }
        } finally {
            lock.unlock();
        }
    }


    /**
     * 处理数据帧（乱序缓存、滑动窗口、交付数据）
     */
    public void handleDataFrame(QuicFrame frame) {
        lock.lock();
        try {
            long seq = frame.getSequence();
            long expectedSeq = recvSequence.get();

            // 重复帧
            if (seq < expectedSeq) {
                metrics.incrementDuplicateFrameCount();
                sendAck(expectedSeq - 1); // 重发ACK
                return;
            }

            // 期望的帧
            if (seq == expectedSeq) {
                // 交付数据
                deliverFrame(frame);
                recvSequence.incrementAndGet();

                // 检查乱序缓存中的连续帧
                while (recvBuffer.containsKey(recvSequence.get())) {
                    QuicFrame cachedFrame = recvBuffer.remove(recvSequence.get());
                    deliverFrame(cachedFrame);
                    recvSequence.incrementAndGet();
                }

                // 发送ACK（批量）
                addToBatchAck(seq);
            } else {
                // 乱序帧，加入缓存
                if (seq < expectedSeq + recvWindowSize) { // 接收窗口内
                    recvBuffer.put(seq, frame);
                    metrics.incrementOutOfOrderFrameCount();
                    addToBatchAck(expectedSeq - 1); // ACK已接收的最大连续序列号
                } else {
                    // 接收窗口外，丢弃
                    metrics.incrementWindowOutOfFrameCount();
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 添加到批量ACK列表
     */
    private void addToBatchAck(long seq) {
        ackList.add(seq);
        if (ackList.size() >= QuicConstants.BATCH_ACK_THRESHOLD) {
            sendBatchAck();
        }
    }

    /**
     * 交付数据到应用层（可扩展为回调）
     */
    private void deliverFrame(QuicFrame frame) {
        // TODO: 实际应用中替换为业务回调
        ByteBuf payload = frame.getPayload();
        if (payload != null) {
            metrics.incrementDeliverBytes(payload.readableBytes());
            payload.release();
        }
        metrics.incrementDeliverFrameCount();
    }
    
    /**
     * 关闭流
     */
    public void close() {
        lock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;

            // 取消定时器
            retransmitTimer.cancel();
            batchAckTimer.cancel();

            // 发送流关闭帧
            QuicFrame closeFrame = QuicFrame.acquire();
            closeFrame.setConnectionId(connection.getConnectionId());
            closeFrame.setStreamId(streamId);
            closeFrame.setFrameType(QuicConstants.FRAME_TYPE_STREAM_CLOSE);
            closeFrame.setRemoteAddress(connection.getRemoteAddress());
            connection.sendFrame(closeFrame);

            // 清理缓冲区
            sendBuffer.values().forEach(QuicFrame::release);
            sendBuffer.clear();
            recvBuffer.values().forEach(QuicFrame::release);
            recvBuffer.clear();
            ackList.clear();

            metrics.decrementStreamCount();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 处理ACK帧（更新未确认序列号、计算RTT、清理发送缓冲区）
     */
    public void handleAckFrame(QuicFrame frame) {
        lock.lock();
        try {
            long ackSeq = frame.getAckSequence();
            int windowSize = frame.getWindowSize();

            // 更新接收窗口
            recvWindowSize = windowSize;

            // 更新未确认序列号
            if (ackSeq >= sndUna.get()) {
                // 计算RTT
                long now = System.currentTimeMillis();
                QuicFrame ackedFrame = sendBuffer.get(ackSeq);
                if (ackedFrame != null) {
                    long rttSample = now - ackedFrame.getSendTime();
                    // 平滑RTT计算：SRTT = (7*SRTT + RTT)/8
                    rtt = (rtt * (QuicConstants.RTT_SMOOTH_FACTOR - 1) + rttSample) / QuicConstants.RTT_SMOOTH_FACTOR;
                    // RTO = SRTT + 4*RTTVAR（简化版）
                    rto = Math.max(QuicConstants.RTO_MIN, Math.min(QuicConstants.RTO_MAX, rtt * 2));
                    metrics.updateRtt(rttSample);
                }

                // 清理已确认的帧
                sendBuffer.headMap(ackSeq + 1).clear();
                sndUna.set(ackSeq + 1);

                // 更新拥塞窗口
                connection.getCongestionControl().onAck();
                sendWindowSize = connection.getCongestionControl().getCwnd();

                metrics.incrementAckCount();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 处理FEC帧（恢复丢失的数据帧）
     */
    public void handleFecFrame(QuicFrame frame) {
        lock.lock();
        try {
            List<QuicFrame> recoveredFrames = fecDecoder.decode(frame);
            for (QuicFrame recoveredFrame : recoveredFrames) {
                handleDataFrame(recoveredFrame);
                metrics.incrementFecRecoverCount();
            }
        } finally {
            lock.unlock();
        }
    }


    /**
     * 发送二进制数据（核心发送逻辑）
     */
    public void send(ByteBuf data) {
        if (closed) {
            throw new IllegalStateException("Stream " + streamId + " is closed");
        }

        // QPS限流
        if (!qpsLimiter.tryAcquire()) {
            metrics.incrementQpsLimitCount();
            return;
        }

        lock.lock();
        try {
            // 分片发送（适配MTU）
            int mtu = connection.getMtuDetector().getCurrentMtu();
            int maxPayload = mtu - 64; // 减去帧头
            while (data.readableBytes() > 0) {
                int sliceLen = Math.min(data.readableBytes(), maxPayload);
                ByteBuf slice = data.readRetainedSlice(sliceLen);

                // 检查发送窗口
                long nextSeq = sendSequence.get();
                if (nextSeq - sndUna.get() >= sendWindowSize) {
                    metrics.incrementWindowFullCount();
                    data.resetReaderIndex(); // 重置读指针，下次重试
                    slice.release();
                    return;
                }

                // 创建数据帧
                QuicFrame frame = QuicFrame.acquire();
                frame.setConnectionId(connection.getConnectionId());
                frame.setStreamId(streamId);
                frame.setFrameType(QuicConstants.FRAME_TYPE_DATA);
                frame.setSequence(nextSeq);
                frame.setPriority(priority);
                frame.setPayload(slice);
                frame.setRemoteAddress(connection.getRemoteAddress());
                frame.setSendTime(System.currentTimeMillis());

                // 添加到发送缓冲区
                sendBuffer.put(nextSeq, frame);

                // 发送帧
                connection.sendFrame(frame);

                // FEC编码（每N个数据帧生成一个冗余帧）
                fecEncoder.addFrame(frame);
                if (fecEncoder.isGroupFull()) {
                    QuicFrame fecFrame = fecEncoder.encode();
                    connection.sendFrame(fecFrame);
                }

                // 更新序列号
                sendSequence.incrementAndGet();
                metrics.incrementStreamSendCount(streamId);
            }
        } finally {
            lock.unlock();
        }
    }
}
