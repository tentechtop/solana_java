package com.bit.solana.p2p.quic;

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
        QuicFrame quicFrame = null;
        try {
            quicFrame = QuicFrame.decode(buf, remote);
            QuicConnection orCreateConnection = QuicConnectionManager.createOrGetConnection((DatagramChannel)ctx.channel(),local,remote,quicFrame.getConnectionId());
            QuicMsg quicMsg = orCreateConnection.handleFrame(quicFrame);
            if (quicMsg != null){
                // 核心：将QuicMsg注入到下一个处理器
                ctx.fireChannelRead(quicMsg);
            }
        } catch (Exception e) {
            // 4. 解码失败直接丢弃，仅记录日志
            log.warn("QUIC帧解码失败，已丢弃无效数据包", e);
        } finally {
            // 释放DatagramPacket的引用计数，避免内存泄漏
            ReferenceCountUtil.release(datagramPacket);
        }
    }

    // 可选：处理下一个Handler传递过来的异常
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        log.error("QuicServiceHandler异常", cause);
        // 关闭通道（根据业务需求选择是否关闭）
        ctx.close();
    }
}
