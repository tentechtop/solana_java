package com.bit.solana.quic;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import io.netty.util.Timer;
import io.netty.util.TimerTask;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.bit.solana.quic.QuicConnectionManager.*;
import static com.bit.solana.quic.QuicConstants.*;

@Slf4j
@Data
public class SendQuicData extends QuicData {
    // ACK确认集合：记录B已确认的序列号
    private final  Set<Integer> ackedSequences = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private Timeout globalTimeout = null;
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
     * @param maxFrameSize 每帧最多maxFrameSize字节
     * @return
     */
    public static SendQuicData buildFromFullData(long connectionId, long dataId, byte frameType,
                                             ByteBuf fullData,InetSocketAddress remoteAddress, int maxFrameSize) {
        // 前置校验
        if (fullData == null || !fullData.isReadable()) {
            throw new IllegalArgumentException("完整数据不能为空或不可读");
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
                frame.setRemoteAddress(remoteAddress);

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


    public void sendAllFrames() throws InterruptedException {
        if (getFrameArray() == null || getFrameArray().length == 0) {
            log.error("[发送失败] 帧数组为空，连接ID:{} 数据ID:{}", getConnectionId(), getDataId());
            return;
        }

        log.info("[开始发送] 连接ID:{} 数据ID:{} 总帧数:{}", getConnectionId(), getDataId(), getTotal());

        startGlobalTimeoutTimer();

        for (int sequence = 0; sequence < getTotal(); sequence++) {
            QuicFrame frame = getFrameArray()[sequence];
            if (frame != null) {
                CompletableFuture<Void> future = CompletableFuture.runAsync(
                        () -> sendFrame(frame),
                        DATA_SEND_THREAD_POOL // 使用数据发送专用线程池
                );
            }
        }
    }

    /**
     * 发送单个帧
     */
    private void sendFrame(QuicFrame frame) {
        int sequence = frame.getSequence();
        ByteBuf buf = QuicConstants.ALLOCATOR.buffer();
        try {
            frame.encode(buf);
            DatagramPacket packet = new DatagramPacket(buf, frame.getRemoteAddress());
            Global_Channel.writeAndFlush(packet).addListener(future -> {
                // 核心：释放 ByteBuf（无论发送成功/失败）
                if (!future.isSuccess()) {
                    log.error("[帧发送失败] 连接ID:{} 数据ID:{} 序列号:{}",
                            getConnectionId(), getDataId(), sequence, future.cause());
                } else {
                    log.debug("[帧发送成功] 连接ID:{} 数据ID:{} 序列号:{}",
                            getConnectionId(), getDataId(), sequence);
                }
            });
        } catch (Exception e) {
            // 异常时直接释放 buf，避免泄漏
            log.error("[帧编码失败] 连接ID:{} 数据ID:{} 序列号:{}",
                    getConnectionId(), getDataId(), sequence, e);
        }

        if (!ackedSequences.contains(sequence)) {
            startFrameRetransmitTimer(sequence);
        }
    }


    /**
     * 启动全局超时定时器
     */
    private void startGlobalTimeoutTimer() {
        synchronized (this) {
            if (globalTimeout == null) {
                globalTimeout = GLOBAL_TIMER.newTimeout(new TimerTask() {
                    @Override
                    public void run(Timeout timeout) throws Exception {
                        if (timeout.isCancelled()) {
                            return;
                        }
                        log.info("[全局超时] 连接ID:{} 数据ID:{} 在{}ms内未完成发送，发送失败",
                                getConnectionId(), getDataId(), GLOBAL_TIMEOUT_MS);
                        handleSendFailure();
                    }
                }, GLOBAL_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);

                log.debug("[全局定时器启动] 连接ID:{} 数据ID:{} 超时时间:{}ms",
                        getConnectionId(), getDataId(), GLOBAL_TIMEOUT_MS);
            }
        }
    }


    /**
     * 处理发送失败
     */
    private void handleSendFailure() {
        log.info("[处理发送失败] 连接ID:{} 数据ID:{}", getConnectionId(), getDataId());
        // 从发送Map中移除
        deleteSendDataByConnectIdAndDataId(getConnectionId(), getDataId());
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
     * 启动单帧重传定时器
     */
    private void startFrameRetransmitTimer(int sequence) {
        cancelRetransmitTimer(sequence);
        // 使用重传专用定时器
        Timeout timeout = RETRANSMIT_TIMER.newTimeout(new TimerTask() {
            @Override
            public void run(Timeout timeout) throws Exception {
                if (timeout.isCancelled() || ackedSequences.contains(sequence)) {
                    return;
                }
                retransmitFrame(sequence);
            }
        }, RETRANSMIT_INTERVAL_MS, TimeUnit.MILLISECONDS);
        retransmitTimers.put(sequence, timeout);
    }


    /**
     * 取消单个重传定时器
     */
    private void cancelRetransmitTimer(int sequence) {
        Timeout timeout = retransmitTimers.remove(sequence);
        if (timeout != null) {
            timeout.cancel();
        }
        //retransmitCounts.remove(sequence);
    }

    /**
     * 重传指定帧
     */
    private void retransmitFrame(int sequence) {
        // 关键：如果已收到ACK，直接返回，不重传
        if (ackedSequences.contains(sequence)) {
            log.debug("[无需重传] 连接ID:{} 数据ID:{} 序列号:{} 已确认",
                    getConnectionId(), getDataId(), sequence);
            return;
        }

        // 1. 初始化计数（仅首次），后续直接获取
        AtomicInteger count = retransmitCounts.computeIfAbsent(sequence, k -> new AtomicInteger(0));
        // 2. 先检查次数是否超限（当前已重传次数）
        if (count.get() >= MAX_RETRANSMIT_TIMES) {
            log.error("[重传超限] 连接ID:{} 数据ID:{} 序列号:{} 重传次数{}≥{}，发送失败",
                    getConnectionId(), getDataId(), sequence, count.get(), MAX_RETRANSMIT_TIMES);
            handleSendFailure();
            return;
        }

        // 3. 获取要重传的帧
        QuicFrame frame = getFrameBySequence(sequence);
        if (frame == null) {
            log.error("[重传帧不存在] 连接ID:{} 数据ID:{} 序列号:{}",
                    getConnectionId(), getDataId(), sequence);
            return;
        }

        // 4. 累加重传次数（先加后用）
        int currentCount = count.incrementAndGet();
        log.info("[开始重传] 连接ID:{} 数据ID:{} 序列号:{} 重传次数:{}",
                getConnectionId(), getDataId(), sequence, currentCount);

        // 5. 重新启动重传定时器并发送帧
        startFrameRetransmitTimer(sequence);
        sendFrame(frame);
    }



    /**
     * 处理ACK帧
     * @param ackFrame ACK帧
     */
    public void handleAckFrame(QuicFrame ackFrame) {
        long connectionId = ackFrame.getConnectionId();//连接ID
        long dataId = ackFrame.getDataId();//数据ID
        int sequence = ackFrame.getSequence();//确认的序列号
        onAckReceived(sequence);
        ackFrame.release();

/*        log.info("连接{} 数据{} 序列号{}",connectionId,dataId,sequence);
        if (dataId != this.getDataId()) {
            log.warn("[ACK数据ID不匹配] 期望:{} 实际:{}", this.getDataId(), dataId);
            return;
        }
        // 如果该序列号已确认，直接返回
        if (ackedSequences.contains(sequence)) {
            log.debug("[重复ACK] 连接ID:{} 数据ID:{} 序列号:{} 已确认", getConnectionId(), getDataId(), sequence);
            return;
        }

        ackedSequences.add(sequence);
        log.info("[ACK确认] 连接ID:{} 数据ID:{} 序列号:{} 已确认:{}/{}",
                connectionId, dataId, sequence, ackedSequences.size(), getTotal());
        cancelRetransmitTimer(sequence);

        // 4. 检查是否所有帧都已确认
        if (ackedSequences.size() == getTotal()) {
            log.info("[发送完成] 连接ID:{} 数据ID:{} 所有帧已确认", connectionId, dataId);
            // 取消全局定时器和所有剩余重传定时器
            cancelAllTimers();
            // 释放资源并清理缓存
            this.release();
            deleteSendDataByConnectIdAndDataId(connectionId, dataId);
            // 执行成功回调
            if (successCallback != null) {
                successCallback.run();
            }
        }*/

    }


    /**
     * 处理ACK确认：标记序列号为已确认并取消重传定时器
     */
    public void onAckReceived(int sequence) {
        // 仅处理未确认的序列号
        if (ackedSequences.add(sequence)) {
            log.debug("[ACK处理] 连接ID:{} 数据ID:{} 序列号:{} 已确认，取消重传定时器",
                    getConnectionId(), getDataId(), sequence);
            // 取消该序列号的重传定时器
            cancelRetransmitTimer(sequence);

            // 检查是否所有帧都已确认
            if (ackedSequences.size() == getTotal()) {
                log.info("[所有帧确认] 连接ID:{} 数据ID:{} 传输完成", getConnectionId(), getDataId());
                handleSendSuccess();
            }
        }
    }

    /**
     * 处理发送成功（所有帧均被确认）
     */
    private void handleSendSuccess() {
        // 取消全局超时定时器
        if (globalTimeout != null) {
            globalTimeout.cancel();
            globalTimeout = null;
        }
        // 清理资源
        cancelAllTimers();
        release();
        // 从发送缓存中移除
        deleteSendDataByConnectIdAndDataId(getConnectionId(), getDataId());
        // 执行成功回调
        if (successCallback != null) {
            successCallback.run();
        }
    }



}
