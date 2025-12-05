package com.bit.solana.quic;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.CharsetUtil;

import java.net.InetSocketAddress;

public class QuicServiceHandler extends SimpleChannelInboundHandler<QuicFrame> {

    /**
     * 处理客户端发送的UDP数据包
     */
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, QuicFrame quicFrame) throws Exception {
        InetSocketAddress local = (InetSocketAddress) ctx.channel().localAddress();
        InetSocketAddress remote = quicFrame.getRemoteAddress();
        QuicConnection orCreateConnection = QuicConnectionManager.getOrCreateConnection(
                (DatagramChannel) ctx.channel(), local, remote);
        orCreateConnection.handleFrame(quicFrame);
    }



}
