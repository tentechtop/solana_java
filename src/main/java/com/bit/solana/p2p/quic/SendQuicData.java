package com.bit.solana.p2p.quic;

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

import static com.bit.solana.p2p.quic.QuicConnectionManager.Global_Channel;
import static com.bit.solana.p2p.quic.QuicConstants.*;


@Slf4j
@Data
public class SendQuicData extends QuicData {
    // ACK确认集合：记录B已确认的序列号
    private final  Set<Integer> ackedSequences = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private Timeout globalTimeout = null;

    // 传输完成回调
    private Runnable successCallback;
    // 传输失败回调
    private Runnable failCallback;


    /**
     * 构建发送数据
     * @param connectionId
     * @param dataId
     * @param sendData 需要发送的数据
     * @param maxFrameSize 每帧最多maxFrameSize字节
     * @return
     */
    public static SendQuicData buildFromFullData(long connectionId, long dataId,
                                             byte[] sendData,InetSocketAddress remoteAddress, int maxFrameSize) {
        // 前置校验
        if (sendData == null) {
            throw new IllegalArgumentException("数据不能为空");
        }
        if (maxFrameSize <= 0) {
            throw new IllegalArgumentException("单帧最大帧载荷长度必须大于0，当前值: " + maxFrameSize);
        }
        if (remoteAddress == null) {
            throw new IllegalArgumentException("目标地址不能为空");
        }

        SendQuicData quicData = new SendQuicData();
        quicData.setConnectionId(connectionId);
        quicData.setDataId(dataId);

        int fullDataLength = sendData.length;
        int totalFrames = (fullDataLength + maxFrameSize - 1) / maxFrameSize;
        // 计算分片总数：向上取整（避免因整数除法丢失最后一个不完整分片）
        quicData.setTotal(totalFrames);//分片总数
        quicData.setFrameArray(new QuicFrame[totalFrames]);

        // 2. 分片构建QuicFrame
        try {
            for (int sequence = 0; sequence < totalFrames; sequence++) {
                // 创建帧实例
                QuicFrame frame =  QuicFrame.acquire();
                frame.setConnectionId(connectionId);
                frame.setDataId(dataId);
                frame.setTotal(totalFrames);
                frame.setFrameType(QuicFrameEnum.DATA_FRAME.getCode());
                frame.setSequence(sequence);
                frame.setRemoteAddress(remoteAddress);

                int startIndex = sequence * maxFrameSize;
                int endIndex = Math.min(startIndex + maxFrameSize, fullDataLength);
                int currentPayloadLength = endIndex - startIndex;

                // 截取载荷数据（从原始数据复制对应区间）
                byte[] payload = new byte[currentPayloadLength];
                System.arraycopy(sendData, startIndex, payload, 0, currentPayloadLength);
                // 设置帧的总长度（固定头部 + 实际载荷长度）
                frame.setFrameTotalLength(QuicFrame.FIXED_HEADER_LENGTH + currentPayloadLength);
                frame.setPayload(payload);
                quicData.getFrameArray()[sequence] = frame;
                log.debug("构建分片完成: connectionId={}, dataId={}, 序列号={}, 载荷长度={}, 总长度={}",
                        connectionId, dataId, sequence, currentPayloadLength, frame.getFrameTotalLength());
            }
        } catch (Exception e) {
            log.error("构建QuicData失败 connectionId={}, dataId={}", connectionId, dataId, e);
            // 异常时释放已创建的帧
            throw new RuntimeException("构建QuicData失败", e);
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
                sendFrame(frame);
            }
        }
    }

    /**
     * 发送单个帧
     */
    private void sendFrame(QuicFrame frame) {
        Thread.ofVirtual()
                .name("send-virtual-thread")
                .start(() -> {
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
                                handleSendFailure();
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
                });
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

    }




    public void onAckReceived(int sequence) {
        // 仅处理未确认的序列号
        if (ackedSequences.add(sequence)) {
            log.debug("[ACK处理] 连接ID:{} 数据ID:{} 序列号:{} 已确认，取消重传定时器",
                    getConnectionId(), getDataId(), sequence);

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
        //释放掉所有的帧
        release();
        // 从发送缓存中移除
        deleteSendDataByConnectIdAndDataId(getConnectionId(), getDataId());
        // 执行成功回调
        if (successCallback != null) {
            successCallback.run();
        }
    }



}
