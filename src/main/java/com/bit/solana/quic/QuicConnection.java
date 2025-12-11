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

import static com.bit.solana.quic.QuicConnectionManager.ReceiveMap;
import static com.bit.solana.quic.QuicConstants.*;
import static com.bit.solana.quic.QuicFrameEnum.DATA_FRAME;


@Slf4j
@Data
public class QuicConnection {
    private long connectionId;// 连接ID
    private DatagramChannel channel;// UDP通道
    private Channel tcpChannel;// TCP通道
    private boolean isUDP = true;//默认使用可靠UDP
    private  InetSocketAddress localAddress;// 本地地址
    private  volatile InetSocketAddress remoteAddress; // 远程地址,支持连接迁移





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
            default:
                break;
        }
        log.info("处理帧{}", quicFrame);
        if (quicFrame != null) {
            quicFrame.release();
        }
    }
}
