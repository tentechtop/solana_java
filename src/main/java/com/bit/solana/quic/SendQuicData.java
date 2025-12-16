package com.bit.solana.quic;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import io.netty.util.Timer;
import io.netty.util.TimerTask;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static com.bit.solana.quic.QuicConnectionManager.*;
import static com.bit.solana.quic.QuicConstants.*;

@Slf4j
@Data
public class SendQuicData extends QuicData {
    // ACK确认集合：记录B已确认的序列号
    private final Set<Integer> ackedSequences = Collections.newSetFromMap(new ConcurrentHashMap<>());


    private Timeout globalTimeout;
    // 单帧重传定时器（序列号→定时器）
    private final ConcurrentHashMap<Integer, Timeout> retransmitTimers = new ConcurrentHashMap<>();
    // 单帧重传次数（序列号→次数）
    private final ConcurrentHashMap<Integer, AtomicInteger> retransmitCounts = new ConcurrentHashMap<>();
    // 传输完成回调
    private Runnable successCallback;
    // 传输失败回调
    private Runnable failCallback;


    /**
     * 构建发送数据
     * @param connectionId
     * @param dataId
     * @param frameType
     * @param fullData
     * @param maxFrameSize
     * @return
     */
    public static SendQuicData buildFromFullData(long connectionId, long dataId, byte frameType,
                                             ByteBuf fullData, int maxFrameSize) {
        // 前置校验
        if (fullData == null || !fullData.isReadable()) {
            throw new IllegalArgumentException("完整数据不能为空或不可读");
        }
        if (maxFrameSize < 1) {
            throw new IllegalArgumentException("单帧最大载荷长度必须≥1，实际：" + maxFrameSize);
        }

        SendQuicData quicData = new SendQuicData();
        quicData.setConnectionId(connectionId);
        quicData.setDataId(dataId);

        // 1. 计算分片总数和每帧长度
        int fullDataLength = fullData.readableBytes();
        // 单个帧的总长度 = 固定头部 + 最大载荷长度
        int singleFrameTotalLength = QuicFrame.FIXED_HEADER_LENGTH + maxFrameSize;
        // 计算需要的分片数（向上取整）
        int totalFrames = (fullDataLength + maxFrameSize - 1) / maxFrameSize;
        quicData.setTotal(totalFrames);
        quicData.setFrameArray(new QuicFrame[totalFrames]);

        // 2. 分片构建QuicFrame
        fullData.markReaderIndex(); // 标记原始读取位置，避免修改外部缓冲区
        try {
            for (int sequence = 0; sequence < totalFrames; sequence++) {
                // 创建帧实例
                QuicFrame frame = QuicFrame.acquire();//生命周期释放
                frame.setConnectionId(connectionId);
                frame.setDataId(dataId);
                frame.setTotal(totalFrames);
                frame.setFrameType(frameType);
                frame.setSequence(sequence);

                // 计算当前帧的载荷长度
                int remainingBytes = fullData.readableBytes();
                int currentPayloadLength = Math.min(remainingBytes, maxFrameSize);
                // 设置帧总长度（固定头部 + 当前载荷长度）
                frame.setFrameTotalLength(QuicFrame.FIXED_HEADER_LENGTH + currentPayloadLength);

                // 复制载荷数据（避免引用外部缓冲区）
                ByteBuf payload = ALLOCATOR.buffer(currentPayloadLength);
                fullData.readBytes(payload, currentPayloadLength);
                frame.setPayload(payload);

                // 将帧存入数组
                quicData.getFrameArray()[sequence] = frame;
            }
            quicData.setComplete(true);
        } catch (Exception e) {
            log.error("构建QuicData失败 connectionId={}, dataId={}", connectionId, dataId, e);
            // 异常时释放已创建的帧
            for (QuicFrame frame : quicData.getFrameArray()) {
                if (frame != null) {
                    frame.release();
                }
            }
            throw new RuntimeException("构建QuicData失败", e);
        } finally {
            fullData.resetReaderIndex(); // 恢复外部缓冲区读取位置
        }
        return quicData;
    }



    /**
     * 发送所有数据帧
     * @param ctx Channel上下文
     * @param remoteAddress 目标地址
     */
    public void sendAllFrames(ChannelHandlerContext ctx, InetSocketAddress remoteAddress) {
        if (getFrameArray() == null || getFrameArray().length == 0) {
            log.error("[发送失败] 帧数组为空，连接ID:{} 数据ID:{}", getConnectionId(), getDataId());
            return;
        }

        log.info("[开始发送] 连接ID:{} 数据ID:{} 总帧数:{}", getConnectionId(), getDataId(), getTotal());
        
        // 启动全局超时定时器
        startGlobalTimeoutTimer(ctx);
        
        // 遍历发送所有帧
        for (int sequence = 0; sequence < getTotal(); sequence++) {
            QuicFrame frame = getFrameArray()[sequence];
            if (frame != null) {
                // 设置远程地址
                frame.setRemoteAddress(remoteAddress);
                // 发送帧
                sendFrame(ctx, frame);
            }
        }
    }

    /**
     * 处理ACK帧
     * @param ctx Channel上下文
     * @param ackFrame ACK帧
     */
    public void handleAckFrame(ChannelHandlerContext ctx, QuicFrame ackFrame) {
        long connectionId = ackFrame.getConnectionId();
        long dataId = ackFrame.getDataId();
        
        // 解析ACK载荷
        ByteBuf payload = ackFrame.getPayload();
        if (payload == null || payload.readableBytes() < 17) { // 最小载荷: dataId(8) + sequence(4) + isReceive(1) + receivedCount(4)
            log.error("[ACK解析失败] 载荷无效，连接ID:{} 数据ID:{}", connectionId, dataId);
            return;
        }

        long ackDataId = payload.readLong();
        int sequence = payload.readInt();
        boolean isReceive = payload.readBoolean();
        int receivedCount = payload.readInt();

        // 校验数据ID是否匹配
        if (ackDataId != this.getDataId()) {
            log.warn("[ACK数据ID不匹配] 期望:{} 实际:{}", this.getDataId(), ackDataId);
            return;
        }

        if (isReceive) {
            // 确认接收成功
            processAckSequence(ctx, sequence, receivedCount);
        } else {
            // 接收失败，需要重传
            log.warn("[ACK接收失败] 连接ID:{} 数据ID:{} 序列号:{} 已接收数:{}", 
                    connectionId, dataId, sequence, receivedCount);
        }
    }

    /**
     * 处理重传请求帧
     * @param ctx Channel上下文
     * @param askFrame 重传请求帧
     */
    public void handleRetransmitAskFrame(ChannelHandlerContext ctx, QuicFrame askFrame) {
        long connectionId = askFrame.getConnectionId();
        long dataId = askFrame.getDataId();
        
        // 解析重传请求载荷
        ByteBuf payload = askFrame.getPayload();
        if (payload == null || payload.readableBytes() < 16) { // 最小载荷: dataId(8) + missingSeq(4) + retransmitCount(4)
            log.error("[重传请求解析失败] 载荷无效，连接ID:{} 数据ID:{}", connectionId, dataId);
            return;
        }

        long askDataId = payload.readLong();
        int missingSeq = payload.readInt();
        int retransmitCount = payload.readInt();

        // 校验数据ID是否匹配
        if (askDataId != this.getDataId()) {
            log.warn("[重传请求数据ID不匹配] 期望:{} 实际:{}", this.getDataId(), askDataId);
            return;
        }

        // 重传指定的帧
        retransmitFrame(ctx, missingSeq, retransmitCount);
    }

    /**
     * 处理ACK序列号确认
     */
    private void processAckSequence(ChannelHandlerContext ctx, int sequence, int receivedCount) {
        // 如果该序列号已确认，直接返回
        if (ackedSequences.contains(sequence)) {
            log.debug("[重复ACK] 连接ID:{} 数据ID:{} 序列号:{} 已确认", getConnectionId(), getDataId(), sequence);
            return;
        }

        // 添加到已确认集合
        ackedSequences.add(sequence);
        // 取消该帧的重传定时器
        cancelRetransmitTimer(sequence);

        log.info("[ACK确认] 连接ID:{} 数据ID:{} 序列号:{} 已确认:{}/{}", 
                getConnectionId(), getDataId(), sequence, ackedSequences.size(), getTotal());

        // 检查是否所有帧都已确认
        if (ackedSequences.size() == getTotal()) {
            handleSendComplete(ctx);
        }
    }

    /**
     * 发送单个帧
     */
    private void sendFrame(ChannelHandlerContext ctx, QuicFrame frame) {
        int sequence = frame.getSequence();
        
        // 为该帧启动重传定时器（仅在首次发送时）
        if (!ackedSequences.contains(sequence)) {
            startFrameRetransmitTimer(ctx, sequence);
        }

        // 发送帧
        ctx.writeAndFlush(frame).addListener(future -> {
            if (!future.isSuccess()) {
                log.error("[帧发送失败] 连接ID:{} 数据ID:{} 序列号:{}", 
                        getConnectionId(), getDataId(), sequence, future.cause());
            } else {
                log.debug("[帧发送成功] 连接ID:{} 数据ID:{} 序列号:{}", 
                        getConnectionId(), getDataId(), sequence);
            }
        });
    }

    /**
     * 重传指定帧
     */
    private void retransmitFrame(ChannelHandlerContext ctx, int sequence, int retransmitCount) {
        // 检查重传次数是否超限
        AtomicInteger count = retransmitCounts.computeIfAbsent(sequence, k -> new AtomicInteger(0));
        int currentCount = count.get();

        if (currentCount >= MAX_RETRANSMIT_TIMES) {
            log.error("[重传超限] 连接ID:{} 数据ID:{} 序列号:{} 重传次数{}≥{}，发送失败",
                    getConnectionId(), getDataId(), sequence, currentCount, MAX_RETRANSMIT_TIMES);
            handleSendFailure(ctx);
            return;
        }

        // 获取要重传的帧
        QuicFrame frame = getFrameBySequence(sequence);
        if (frame == null) {
            log.error("[重传帧不存在] 连接ID:{} 数据ID:{} 序列号:{}", 
                    getConnectionId(), getDataId(), sequence);
            return;
        }

        // 增加重传次数
        count.incrementAndGet();
        log.info("[开始重传] 连接ID:{} 数据ID:{} 序列号:{} 重传次数:{}", 
                getConnectionId(), getDataId(), sequence, count.get());

        // 重新启动重传定时器并发送帧
        startFrameRetransmitTimer(ctx, sequence);
        sendFrame(ctx, frame);
    }

    /**
     * 启动单帧重传定时器
     */
    private void startFrameRetransmitTimer(ChannelHandlerContext ctx, int sequence) {
        // 取消已有的定时器
        cancelRetransmitTimer(sequence);

        // 创建新的重传定时器
        Timeout timeout = GLOBAL_TIMER.newTimeout(new TimerTask() {
            @Override
            public void run(Timeout timeout) throws Exception {
                if (timeout.isCancelled() || isComplete()) {
                    return;
                }
                // 定时器触发，重传该帧
                AtomicInteger count = retransmitCounts.get(sequence);
                int currentCount = count != null ? count.get() : 0;
                retransmitFrame(ctx, sequence, currentCount);
            }
        }, RETRANSMIT_INTERVAL_MS, java.util.concurrent.TimeUnit.MILLISECONDS);

        retransmitTimers.put(sequence, timeout);
    }

    /**
     * 启动全局超时定时器
     */
    private void startGlobalTimeoutTimer(ChannelHandlerContext ctx) {
        synchronized (this) {
            if (globalTimeout == null && !isComplete()) {
                globalTimeout = GLOBAL_TIMER.newTimeout(new TimerTask() {
                    @Override
                    public void run(Timeout timeout) throws Exception {
                        if (timeout.isCancelled() || isComplete()) {
                            return;
                        }
                        log.error("[全局超时] 连接ID:{} 数据ID:{} 在{}ms内未完成发送，发送失败",
                                getConnectionId(), getDataId(), GLOBAL_TIMEOUT_MS);
                        handleSendFailure(ctx);
                    }
                }, GLOBAL_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
                log.info("[全局定时器启动] 连接ID:{} 数据ID:{} 超时时间:{}ms",
                        getConnectionId(), getDataId(), GLOBAL_TIMEOUT_MS);
            }
        }
    }

    /**
     * 处理发送完成
     */
    private void handleSendComplete(ChannelHandlerContext ctx) {
        if (this.isComplete()) {
            return;
        }
        setComplete(true);
        log.info("[发送完成] 连接ID:{} 数据ID:{}", getConnectionId(), getDataId());

        // 从发送Map中移除
        SendMap.remove(getDataId());
        // 取消所有定时器
        cancelAllTimers();
        // 释放帧资源
        this.release();
        // 执行成功回调
        if (successCallback != null) {
            successCallback.run();
        }
    }

    /**
     * 处理发送失败
     */
    private void handleSendFailure(ChannelHandlerContext ctx) {
        if (this.isComplete()) {
            return;
        }
        setComplete(true);
        log.error("[发送失败] 连接ID:{} 数据ID:{}", getConnectionId(), getDataId());

        // 从发送Map中移除
        SendMap.remove(getDataId());
        // 取消所有定时器
        cancelAllTimers();
        // 释放帧资源
        this.release();
        // 执行失败回调
        if (failCallback != null) {
            failCallback.run();
        }
    }

    /**
     * 取消所有定时器
     */
    private void cancelAllTimers() {
        // 取消全局定时器
        if (globalTimeout != null) {
            globalTimeout.cancel();
            globalTimeout = null;
        }
        // 取消所有单帧重传定时器
        retransmitTimers.values().forEach(Timeout::cancel);
        retransmitTimers.clear();
        retransmitCounts.clear();
    }

    /**
     * 取消单个重传定时器
     */
    private void cancelRetransmitTimer(int sequence) {
        Timeout timeout = retransmitTimers.remove(sequence);
        if (timeout != null) {
            timeout.cancel();
        }
        retransmitCounts.remove(sequence);
    }


}
