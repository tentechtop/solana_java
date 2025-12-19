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
        log.info("发送成功");
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


    //立即释放全部的资源
    public void allReceived() {
        handleSendSuccess();
    }

    /**
     * 处理批量ACK：解析字节数组中的比特位，标记对应序列号为已确认
     * 格式约定：每个比特代表一个序列号的确认状态（1=已确认，0=未确认），采用大端序（第0位对应序列号0）
     * @param ackList 批量ACK的比特位数组（长度为 (总帧数+7)/8 向上取整）
     */
    public void batchAck(byte[] ackList) {
        if (ackList == null || ackList.length == 0) {
            log.warn("[批量ACK处理] ACK列表为空，连接ID:{} 数据ID:{}", getConnectionId(), getDataId());
            return;
        }

        int totalFrames = getTotal();
        int expectedByteLength = (totalFrames + 7) / 8; // 计算预期的字节长度

        // 校验ACK列表长度是否匹配总帧数（防止恶意或错误的ACK数据）
        if (ackList.length != expectedByteLength) {
            log.warn("[批量ACK处理] ACK列表长度不匹配，总帧数:{} 预期字节数:{} 实际字节数:{}，连接ID:{} 数据ID:{}",
                    totalFrames, expectedByteLength, ackList.length, getConnectionId(), getDataId());
            return;
        }

        int confirmedCount = 0; // 统计本次批量确认的新序列号数量

        // 遍历每个字节解析比特位
        for (int byteIndex = 0; byteIndex < ackList.length; byteIndex++) {
            byte ackByte = ackList[byteIndex];

            // 遍历当前字节的8个比特（大端序：bitIndex=0对应最高位，代表序列号 byteIndex*8 + 0）
            for (int bitIndex = 0; bitIndex < 8; bitIndex++) {
                int sequence = byteIndex * 8 + bitIndex; // 计算对应的序列号

                // 序列号超出总帧数范围时终止（最后一个字节可能有无效比特）
                if (sequence >= totalFrames) {
                    break;
                }

                // 检查当前比特是否为1（已确认）
                boolean isAcked = (ackByte & (1 << (7 - bitIndex))) != 0;
                if (isAcked) {
                    // 调用已有的单ACK处理逻辑（自动去重并检查是否全部确认）
                    if (ackedSequences.add(sequence)) {
                        confirmedCount++;
                        log.debug("[批量ACK确认] 连接ID:{} 数据ID:{} 序列号:{} 已确认",
                                getConnectionId(), getDataId(), sequence);
                    }
                }
            }
        }

        log.info("[批量ACK处理完成] 连接ID:{} 数据ID:{} 总帧数:{} 本次确认新序列号:{} 累计确认:{}",
                getConnectionId(), getDataId(), totalFrames, confirmedCount, ackedSequences.size());

        // 检查是否所有帧都已确认（触发完成逻辑）
        if (ackedSequences.size() == totalFrames) {
            log.info("[所有帧通过批量ACK确认] 连接ID:{} 数据ID:{} 传输完成", getConnectionId(), getDataId());
            handleSendSuccess();
        }
    }
}
