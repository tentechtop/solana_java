package com.bit.solana.quic;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;

@Slf4j
public class QuicServiceHandler extends SimpleChannelInboundHandler<DatagramPacket> {


    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket datagramPacket) throws Exception {
        //如果一个远程地址出现大量无效帧就断开连接
        ByteBuf buf = datagramPacket.content();
        InetSocketAddress remote = datagramPacket.sender();
        InetSocketAddress local = (InetSocketAddress) ctx.channel().localAddress();
        QuicFrame quicFrame = QuicFrame.decode(buf, remote);
        try {
            QuicConnection orCreateConnection = QuicConnectionManager.createOrGetConnection((DatagramChannel)ctx.channel(),local,remote,quicFrame.getConnectionId());
            orCreateConnection.handleFrame(quicFrame);
        } catch (Exception e) {
            // 4. 解码失败直接丢弃，仅记录日志
            quicFrame.release();
            log.warn("QUIC帧解码失败，已丢弃无效数据包", e);
            return;
        }
    }
}
