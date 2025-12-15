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


import static com.bit.solana.quic.QuicConnectionManager.Global_Channel;
import static com.bit.solana.quic.QuicConstants.*;
import static com.bit.solana.quic.QuicFrameEnum.DATA_FRAME;


@Slf4j
@Data
public class QuicConnection {
    private long connectionId;// 连接ID
    public  DatagramChannel channel = null;// UDP通道
    private Channel tcpChannel;// TCP通道
    private boolean isUDP = true;//默认使用可靠UDP
    private  InetSocketAddress localAddress;// 本地地址
    private  volatile InetSocketAddress remoteAddress; // 远程地址,支持连接迁移
    //心跳任务
    private TimerTask heartbeatTask;




    private void startHeartbeat() {
        // 避免重复启动
        if (heartbeatTask != null) {
            log.warn("心跳任务已在运行，连接ID:{}", connectionId);
            return;
        }

        // 定义心跳任务：每400ms发送PING帧
        heartbeatTask = new TimerTask() {
            @Override
            public void run(Timeout timeout) throws Exception {
                // 连接已关闭则停止心跳
                if (remoteAddress == null || channel == null || !channel.isActive()) {
                    log.info("连接已关闭，停止心跳，连接ID:{}", connectionId);
                    return;
                }

                try {
                    // 构建PING帧（假设帧类型为0x01，需在QuicFrameEnum中定义PING_FRAME）
                    QuicFrame pingFrame = QuicFrame.acquire();
                    pingFrame.setConnectionId(connectionId);
                    pingFrame.setDataId(generator.nextId()); // 临时数据ID
                    pingFrame.setFrameType(QuicFrameEnum.PING_FRAME.getCode()); // PING_FRAME类型
                    pingFrame.setTotal(1); // 单帧无需分片
                    pingFrame.setSequence(0);
                    pingFrame.setFrameTotalLength(QuicFrame.FIXED_HEADER_LENGTH); // 无载荷
                    pingFrame.setRemoteAddress(remoteAddress);

                    // 发送PING帧
                    channel.writeAndFlush(pingFrame).addListener(future -> {
                        if (!future.isSuccess()) {
                            log.error("[心跳发送失败] 连接ID:{}", connectionId, future.cause());
                        } else {
                            log.debug("[心跳发送成功] 连接ID:{}", connectionId);
                        }
                    });
                    //等待PONG


                } catch (Exception e) {
                    log.error("[心跳任务异常] 连接ID:{}", connectionId, e);
                }

                // 继续调度下一次心跳
                if (!timeout.isCancelled()) {
                    TIMER.newTimeout(this, 400, TimeUnit.MILLISECONDS);
                }
            }
        };

        // 启动首次心跳
        TIMER.newTimeout(heartbeatTask, 400, TimeUnit.MILLISECONDS);
        log.info("心跳任务启动，连接ID:{}，间隔:400ms", connectionId);
    }

    private void stopHeartbeat() {
        if (heartbeatTask != null) {
            // 取消所有未执行的心跳任务（通过Timer的特性，直接取消当前任务链）
            // 注：HashedWheelTimer的Timeout可通过cancel()取消
            // 这里通过重新赋值为空标记停止，实际任务会在下次检查连接状态时终止
            heartbeatTask = null;
            log.info("心跳任务已停止，连接ID:{}", connectionId);
        }
    }




    //发送二进制数据 二进制到QuicData
    //处理数据帧 返回ACK帧
    private void handleDataFrame(ChannelHandlerContext ctx,QuicFrame quicFrame) {
        //判断receiveMap中是否存在
        if (ReceiveMap.containsKey(quicFrame.getDataId())){
            //存在
            ReceiveQuicData receiveQuicData = ReceiveMap.get(quicFrame.getDataId());
            receiveQuicData.handleFrame(ctx,quicFrame);
        }else {
            //不存在
            //创建ReceiveQuicData
            ReceiveQuicData receiveQuicData = new ReceiveQuicData();
            //TODO
            //添加到ReceiveMap中
            ReceiveMap.put(quicFrame.getDataId(),receiveQuicData);
            receiveQuicData.handleFrame(ctx,quicFrame);
        }
    }

    //处理ACK帧


    public void handleFrame(ChannelHandlerContext ctx,QuicFrame quicFrame) {

        switch (QuicFrameEnum.fromCode(quicFrame.getFrameType())) {
            case DATA_FRAME:
                handleDataFrame(ctx,quicFrame);
            case ACK_FRAME:
                handleACKFrame(ctx,quicFrame);
            case PING_FRAME:
                handlePingFrame(ctx,quicFrame);
            case OFF_FRAME:
                handleOffFrame(ctx,quicFrame);
            case CONNECT_REQUEST_FRAME:
                handleConnectRequestFrame(ctx,quicFrame);
            case CONNECT_RESPONSE_FRAME:
                handleConnectResponseFrame(ctx,quicFrame);

            default:
                break;
        }
        log.info("处理帧{}", quicFrame);
        quicFrame.release();
    }

    private void handleConnectRequestFrame(ChannelHandlerContext ctx, QuicFrame quicFrame) {
        log.info("处理连接请求");
    }

    private void handleConnectResponseFrame(ChannelHandlerContext ctx, QuicFrame quicFrame) {
        log.info("处理连接响应");
        //核销掉这个数据
        CompletableFuture<byte[]> ifPresent = RESPONSE_FUTURECACHE.asMap().remove(quicFrame.getDataId());
        if (ifPresent != null) {
            ifPresent.complete(null);
        }
    }



    private void handleOffFrame(ChannelHandlerContext ctx, QuicFrame quicFrame) {
        log.info("处理节点下线");
        //取消心跳关闭连接

    }

    private void handlePingFrame(ChannelHandlerContext ctx, QuicFrame quicFrame) {
        log.info("处理ping");
        //回复PONG
    }

    private void handleACKFrame(ChannelHandlerContext ctx, QuicFrame quicFrame) {
        log.info("处理ACK");
    }
}
