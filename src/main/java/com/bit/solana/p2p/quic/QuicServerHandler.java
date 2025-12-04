package com.bit.solana.p2p.quic;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;

import java.net.InetSocketAddress;

/**
 * 服务器处理器
 */
public class QuicServerHandler extends SimpleChannelInboundHandler<QuicFrame> {
    private final QuicMetrics metrics = new QuicMetrics();


    @Override
    protected void channelRead0(ChannelHandlerContext ctx, QuicFrame quicFrame) throws Exception {
        InetSocketAddress local = (InetSocketAddress) ctx.channel().localAddress();
        InetSocketAddress remote = quicFrame.getRemoteAddress();

        // 获取或创建连接
        QuicConnection connection = QuicConnectionManager.getOrCreateConnection(
                (DatagramChannel) ctx.channel(), local, remote, metrics);
        connection.handleFrame(quicFrame);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        metrics.incrementInvalidFrameCount();
        ctx.close();
    }

    public QuicMetrics getMetrics() { return metrics; }
}
