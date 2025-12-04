package com.bit.solana.p2p.quic;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.CharsetUtil;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;

/**
 * 服务器处理器
 */
@Slf4j
public class QuicServiceHandler extends SimpleChannelInboundHandler<QuicFrame>  {
    private final QuicMetrics metrics = new QuicMetrics();
    private QuicConnection connection;


    @Override
    protected void channelRead0(ChannelHandlerContext ctx, QuicFrame quicFrame) throws Exception {
        //激活连接
        InetSocketAddress local = (InetSocketAddress) ctx.channel().localAddress();
        InetSocketAddress remote = quicFrame.getRemoteAddress();

        // 获取或创建连接

        connection = QuicConnectionManager.getOrCreateConnection(
                (DatagramChannel) ctx.channel(), local, remote, metrics);
        connection.handleFrame(quicFrame);
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
