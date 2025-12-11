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
public class QuicServiceHandler extends SimpleChannelInboundHandler<QuicFrame> {

    /**
     * 处理客户端发送的UDP数据包
     */
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, QuicFrame quicFrame) throws Exception {
        InetSocketAddress local = (InetSocketAddress) ctx.channel().localAddress();
        InetSocketAddress remote = quicFrame.getRemoteAddress();
        if (quicFrame == null || !quicFrame.isValid()) {
            log.warn("无效帧，忽略");
            ReferenceCountUtil.safeRelease(quicFrame); // 自动判空，安全释放
            return;
        }


/*        QuicConnection orCreateConnection = QuicConnectionManager.getOrCreateConnection(
                (DatagramChannel) ctx.channel(), local, remote);
        orCreateConnection.handleFrame(ChannelHandlerContext ctx,quicFrame);*/
    }



}
