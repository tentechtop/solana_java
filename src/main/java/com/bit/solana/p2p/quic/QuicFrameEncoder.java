package com.bit.solana.p2p.quic;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.EncoderException;
import io.netty.handler.codec.MessageToByteEncoder;
import io.netty.handler.codec.MessageToMessageEncoder;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.util.List;

@Slf4j
public class QuicFrameEncoder extends MessageToMessageEncoder<QuicFrame> {

    @Override
    protected void encode(ChannelHandlerContext channelHandlerContext, QuicFrame quicFrame, List<Object> out) throws Exception {
        log.info("编码发送的Quic数据包:{} 接收地址{}", quicFrame.getPayload(), quicFrame.getRemoteAddress());
        // 1. 编码帧数据
        ByteBuf buf = QuicConstants.ALLOCATOR.buffer();
        quicFrame.encode(buf); // 使用已有的 encode(ByteBuf) 方法

        // 2. 创建DatagramPacket，指定目标地址（从帧中获取服务端地址）
        DatagramPacket packet = new DatagramPacket(buf, quicFrame.getRemoteAddress());
        out.add(packet); // 发送DatagramPacket
    }
}
