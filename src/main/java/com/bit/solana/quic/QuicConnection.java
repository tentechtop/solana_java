package com.bit.solana.quic;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import io.netty.util.Timer;
import io.netty.util.TimerTask;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.checkerframework.checker.nullness.qual.PolyNull;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;


import static com.bit.solana.quic.QuicConnectionManager.*;
import static com.bit.solana.quic.QuicConstants.*;
import static com.bit.solana.quic.QuicFrameEnum.DATA_FRAME;
import static com.bit.solana.quic.SendQuicData.buildFromFullData;


@Slf4j
@Data
public class QuicConnection {
    private long connectionId;// 连接ID
    private Channel tcpChannel;// TCP通道
    private boolean isUDP = true;//默认使用可靠UDP
    private  volatile InetSocketAddress remoteAddress; // 远程地址,支持连接迁移
    // 心跳/检查任务相关
    private TimerTask connectionTask; // 统一命名：出站=心跳任务，入站=检查任务
    private volatile Timeout connectionTimeout; // 保存定时任务引用，用于取消







    //是否过期
    private volatile boolean expired = false;
    //最后访问时间
    private volatile long lastSeen = System.currentTimeMillis();
    //true 是出站连接 false是入站连接
    private boolean isOutbound;

    /**
     * 启动连接任务（差异化：出站=主动心跳，入站=仅过期检查）
     */
    public void startHeartbeat() {
        // 避免重复启动
        if (connectionTask != null) {
            log.warn("连接任务已在运行，连接ID:{}（出站:{}）", connectionId, isOutbound);
            return;
        }

        // 分支1：出站连接 - 主动心跳（400ms）+ 过期检查
        if (isOutbound) {
            connectionTask = new TimerTask() {
                @Override
                public void run(Timeout timeout) throws Exception {
                    long now = System.currentTimeMillis();
                    // 先检查是否过期
                    if (now - lastSeen > CONNECTION_EXPIRE_TIMEOUT) {
                        markAsExpired();
                        return;
                    }

                    // 连接已失效则终止
                    if (remoteAddress == null || Global_Channel == null || expired) {
                        log.info("出站连接已关闭/过期，停止心跳，连接ID:{}", connectionId);
                        markAsExpired();
                        return;
                    }

                    try {
                        // 主动发送PING帧
                        QuicFrame pingFrame = QuicFrame.acquire();
                        long dataId = generator.nextId();
                        pingFrame.setConnectionId(connectionId);
                        pingFrame.setDataId(dataId);
                        pingFrame.setFrameType(QuicFrameEnum.PING_FRAME.getCode());
                        pingFrame.setTotal(1);
                        pingFrame.setSequence(0);
                        pingFrame.setFrameTotalLength(QuicFrame.FIXED_HEADER_LENGTH);
                        pingFrame.setRemoteAddress(remoteAddress);

                        QuicFrame quicFrame = sendFrame(pingFrame);
                        if (quicFrame == null) {
                            log.warn("出站连接PING帧发送失败，连接ID:{} 时间{}", connectionId,System.currentTimeMillis());
                        } else {
                            log.debug("出站连接PING帧发送成功，连接ID:{}，PONG帧:{}", connectionId, quicFrame);
                            updateLastSeen(); // 接收PONG更新活动时间
                            quicFrame.release();
                        }
                    } catch (Exception e) {
                        log.error("[出站心跳异常] 连接ID:{}", connectionId, e);
                    }

                    // 继续调度下一次心跳（未过期才继续）
                    if (!timeout.isCancelled() && !expired) {
                        connectionTimeout = HEARTBEAT_TIMER.newTimeout(this, OUTBOUND_HEARTBEAT_INTERVAL, TimeUnit.MILLISECONDS);
                    }
                }
            };
            // 启动出站心跳（400ms间隔）
            connectionTimeout = HEARTBEAT_TIMER.newTimeout(connectionTask, OUTBOUND_HEARTBEAT_INTERVAL, TimeUnit.MILLISECONDS);
            log.info("出站连接心跳任务启动，连接ID:{}，心跳间隔:{}ms，过期阈值:{}ms",
                    connectionId, OUTBOUND_HEARTBEAT_INTERVAL, CONNECTION_EXPIRE_TIMEOUT);

            // 分支2：入站连接 - 仅过期检查（1000ms），不主动发PING
        } else {
            connectionTask = new TimerTask() {
                @Override
                public void run(Timeout timeout) throws Exception {
                    long now = System.currentTimeMillis();
                    // 仅检查过期，不发送任何帧
                    if (now - lastSeen > CONNECTION_EXPIRE_TIMEOUT) {
                        markAsExpired();
                        return;
                    }

                    // 连接已失效则终止
                    if (remoteAddress == null || expired) {
                        log.info("入站连接已关闭/过期，停止检查，连接ID:{}", connectionId);
                        markAsExpired();
                        return;
                    }

                    log.debug("入站连接过期检查通过，连接ID:{}（最后活动时间:{}ms前）",
                            connectionId, now - lastSeen);

                    // 继续调度下一次检查（未过期才继续）
                    if (!timeout.isCancelled() && !expired) {
                        connectionTimeout = HEARTBEAT_TIMER.newTimeout(this, INBOUND_CHECK_INTERVAL, TimeUnit.MILLISECONDS);
                    }
                }
            };
            // 启动入站过期检查（1000ms间隔）
            connectionTimeout = HEARTBEAT_TIMER.newTimeout(connectionTask, INBOUND_CHECK_INTERVAL, TimeUnit.MILLISECONDS);
            log.info("入站连接过期检查任务启动，连接ID:{}，检查间隔:{}ms，过期阈值:{}ms",
                    connectionId, INBOUND_CHECK_INTERVAL, CONNECTION_EXPIRE_TIMEOUT);
        }
    }

    /**
     * 停止连接任务（心跳/检查）
     */
    public void stopHeartbeat() {
        // 真正取消定时任务，避免内存泄漏
        if (connectionTimeout != null) {
            connectionTimeout.cancel();
            connectionTimeout = null;
        }
        connectionTask = null;
        log.info("连接任务已停止，连接ID:{}（出站:{}）", connectionId, isOutbound);
    }


    /**
     * 更新最后访问时间（所有帧交互时调用）
     */
    public void updateLastSeen() {
        this.lastSeen = System.currentTimeMillis();
        this.expired = false; // 有活动则重置过期状态
    }

    /**
     * 标记连接过期（核心方法）
     */
    public void markAsExpired() {
        release();
    }


    //基于连接发送一次完整的数据  对方对数据中的每一个帧都回复ACK 表示数据发送成功
    public boolean sendData(byte[] data) {
        long dataId = generator.nextId();
        ByteBuf buffer = null;
        try {
            buffer = ALLOCATOR.buffer();
            buffer.writeBytes(data);
            SendQuicData sendQuicData = buildFromFullData(connectionId, dataId,
                    DATA_FRAME.getCode(), buffer, remoteAddress, MAX_FRAME_PAYLOAD);
            addSendDataToConnect(connectionId, sendQuicData);
            sendQuicData.sendAllFrames();
            return true;
        } catch (Exception e){
            return false;
        }finally {
            // 关键：无论是否异常，最终释放 buffer
            if (buffer != null && buffer.refCnt() > 0) {
                buffer.release();
            }
        }
    }



    //释放该连接
    public void release() {
        if (expired) {
            return;
        }
        expired = true;
        log.warn("连接已过期... 时间{}",System.currentTimeMillis());

        // 1. 移除连接管理器的核心引用（重中之重）
        QuicConnectionManager.removeConnection(connectionId);

        // 2. 彻底清理定时任务（避免Timer持有对象引用）
        if (connectionTimeout != null) {
            connectionTimeout.cancel();
            connectionTimeout = null;
        }
        connectionTask = null; // 清空任务引用

        // 3. 清理Netty通道相关引用（避免IO线程持有）
        if (tcpChannel != null) {
            tcpChannel.close().addListener(future -> {
                tcpChannel = null; // 通道关闭后再置空，避免空指针
            });
        }

        // 4. 清空成员变量引用（辅助GC，减少引用链）
        remoteAddress = null;

        // 5. 清理业务缓存中的引用（关键！如果有其他缓存持有该连接的关联数据）
        // 比如ReceiveMap中如果有该连接的dataId映射，需同步清理

    }







    //发送二进制数据 二进制到QuicData
    //处理数据帧 返回ACK帧
    private void handleDataFrame(QuicFrame quicFrame) {
        boolean receiveDataExistInConnect = isReceiveDataExistInConnect(quicFrame.getConnectionId(), quicFrame.getDataId());
        if (receiveDataExistInConnect){
            //存在就直接获取到
            ReceiveQuicData receiveQuicData = getReceiveDataByConnectIdAndDataId(quicFrame.getConnectionId(), quicFrame.getDataId());
            receiveQuicData.handleFrame(quicFrame);
        }else {
            //不存在就创建
            ReceiveQuicData receiveDataByFrame = createReceiveDataByFrame(quicFrame);
            receiveDataByFrame.handleFrame(quicFrame);
        }
    }

    //处理ACK帧


    public void handleFrame(QuicFrame quicFrame) {
        try {
            switch (QuicFrameEnum.fromCode(quicFrame.getFrameType())) {
                case DATA_FRAME:
                    handleDataFrame(quicFrame);
                    break;
                case ACK_FRAME:
                    handleACKFrame(quicFrame);
                    break;
                case PING_FRAME:
                    handlePingFrame(quicFrame);
                    break;
                case PONG_FRAME:
                    handlePongFrame(quicFrame);
                    break;
                case OFF_FRAME:
                    handleOffFrame(quicFrame);
                    break;
                case CONNECT_REQUEST_FRAME:
                    handleConnectRequestFrame(quicFrame);
                    break;
                case CONNECT_RESPONSE_FRAME:
                    handleConnectResponseFrame(quicFrame);
                default:
                    break;
            }
        }finally {
            quicFrame.release();
        }
    }

    private void handlePongFrame( QuicFrame quicFrame) {
        log.debug("处理PONG帧{}",quicFrame);
        CompletableFuture<Object> ifPresent = RESPONSE_FUTURECACHE.asMap().remove(quicFrame.getDataId());
        if (ifPresent != null) {
            ifPresent.complete(quicFrame);
        }
        quicFrame.release();
    }

    private void handleConnectRequestFrame(QuicFrame quicFrame) {
        log.info("处理连接请求");
        long conId = quicFrame.getConnectionId();
        //发送连接响应帧
        long dataId = quicFrame.getDataId();
        QuicFrame acquire = QuicFrame.acquire();//已经释放
        acquire.setConnectionId(conId);//生成连接ID
        acquire.setDataId(dataId);
        acquire.setTotal(1);
        acquire.setFrameType(QuicFrameEnum.CONNECT_RESPONSE_FRAME.getCode());
        acquire.setFrameTotalLength(QuicFrame.FIXED_HEADER_LENGTH);
        acquire.setPayload(null);
        ByteBuf buffer = ALLOCATOR.buffer();
        acquire.encode(buffer);
        DatagramPacket datagramPacket = new DatagramPacket(buffer, quicFrame.getRemoteAddress());
        Global_Channel.writeAndFlush(datagramPacket).addListener(future -> {
            acquire.release();
            if (!future.isSuccess()) {
                log.error("[连接响应发送失败] 节点ID:{}", conId, future.cause());
            } else {
                log.debug("[连接响应发送成功] 节点ID:{}", conId);
            }
        });
        quicFrame.release();
    }

    private void handleConnectResponseFrame(QuicFrame quicFrame) {
        log.info("处理连接响应{}",quicFrame);
        //核销掉这个数据
        CompletableFuture<Object> ifPresent = RESPONSE_FUTURECACHE.asMap().remove(quicFrame.getDataId());
        if (ifPresent != null) {
            ifPresent.complete(quicFrame);
        }
        quicFrame.release();
    }



    private void handleOffFrame(QuicFrame quicFrame) {
        log.info("处理节点下线");
        //取消心跳关闭连接
        quicFrame.release();
    }

    private void handlePingFrame(QuicFrame quicFrame) {
        log.debug("处理ping");
        //更新访问时间
        //回复PONG帧
        QuicFrame pongFrame = QuicFrame.acquire();//已经释放
        pongFrame.setConnectionId(connectionId);
        pongFrame.setDataId(quicFrame.getDataId()); // 临时数据ID
        pongFrame.setFrameType(QuicFrameEnum.PONG_FRAME.getCode()); // PING_FRAME类型
        pongFrame.setTotal(1); // 单帧无需分片
        pongFrame.setSequence(0);
        pongFrame.setFrameTotalLength(QuicFrame.FIXED_HEADER_LENGTH); // 无载荷
        pongFrame.setRemoteAddress(quicFrame.getRemoteAddress());

        //编码
        ByteBuf buf = QuicConstants.ALLOCATOR.buffer();
        pongFrame.encode(buf);
        DatagramPacket packet = new DatagramPacket(buf, pongFrame.getRemoteAddress());
        Global_Channel.writeAndFlush(packet).addListener(future -> {
            pongFrame.release();
            if (!future.isSuccess()) {
                log.info("回复失败{}", remoteAddress);
            } else {
                log.debug("回复成功{}", remoteAddress);
            }
        });
        quicFrame.release();
    }

    private void handleACKFrame( QuicFrame quicFrame) {
        long connectionId1 = quicFrame.getConnectionId();
        long dataId = quicFrame.getDataId();
        int sequence = quicFrame.getSequence();
        SendQuicData sendQuicData = getSendDataByConnectIdAndDataId(connectionId1, dataId);
        // 标记该序列号为已确认
        if (sendQuicData != null){
            sendQuicData.onAckReceived(sequence);
        }else {
            log.info("数据处理完成了");
            quicFrame.release();
        }
    }
}
