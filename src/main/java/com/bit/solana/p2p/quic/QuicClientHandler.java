package com.bit.solana.p2p.quic;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;

/**
 * 客户端处理器
 */
public class QuicClientHandler extends SimpleChannelInboundHandler<DatagramPacket> {
    private final QuicMetrics metrics = new QuicMetrics();
    private QuicConnection connection;

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        InetSocketAddress local = (InetSocketAddress) ctx.channel().localAddress();
        InetSocketAddress remote = (InetSocketAddress) ctx.channel().remoteAddress();
        connection = QuicConnectionManager.getOrCreateConnection(
                (DatagramChannel) ctx.channel(), local, remote, metrics);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) throws Exception {
        QuicFrame frame = QuicFrame.decode(packet.content().retain(), packet.sender());
        connection.handleFrame(frame);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        metrics.incrementInvalidFrameCount();
        ctx.close();
    }

    // ========== 对外API ==========
    public QuicStream createStream(int streamId, byte priority, long qpsLimit) {
        return connection.createStream(streamId, priority, qpsLimit);
    }

    public QuicMetrics getMetrics() { return metrics; }
}
