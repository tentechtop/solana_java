package com.bit.solana.p2p.quic;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramChannel;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;

@Slf4j
public class QuicClientHandler extends SimpleChannelInboundHandler<QuicFrame> {
    private final QuicMetrics metrics = new QuicMetrics();
    private QuicConnection connection;

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        log.info("连接已激活:{}", ctx.channel().localAddress());
        //而是指当前 UDP 通道自身进入了可读写的活跃状态。
        InetSocketAddress local = (InetSocketAddress) ctx.channel().localAddress();
        InetSocketAddress remote = (InetSocketAddress) ctx.channel().remoteAddress();
        connection = QuicConnectionManager.getOrCreateConnection(
                (DatagramChannel) ctx.channel(), local, remote, metrics);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, QuicFrame quicFrame) throws Exception {
        connection.handleFrame(quicFrame);
    }

/*    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        metrics.incrementInvalidFrameCount();
        ctx.close();
    }*/

    // ========== 对外API ==========
    public QuicStream createStream(int streamId, byte priority, long qpsLimit) {
        return connection.createStream(streamId, priority, qpsLimit);
    }

    public QuicMetrics getMetrics() { return metrics; }
}
