package com.bit.solana.quic;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.CharsetUtil;

/**
 * UDP服务端处理器：处理接收的UDP数据包并回复
 */
public class UdpServerHandler extends SimpleChannelInboundHandler<DatagramPacket> {

    /**
     * 处理客户端发送的UDP数据包
     */
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) throws Exception {
        // 1. 解析客户端发送的消息（DatagramPacket封装了UDP数据包和发送方地址）
        String receiveMsg = packet.content().toString(CharsetUtil.UTF_8);
        System.out.println("服务端接收：" + receiveMsg);

        // 2. 构造响应消息
        String responseMsg = "服务端已收到：" + receiveMsg;
        DatagramPacket responsePacket = new DatagramPacket(
                Unpooled.copiedBuffer(responseMsg, CharsetUtil.UTF_8),
                packet.sender() // 回复到客户端的地址
        );

        // 3. 发送响应
        ctx.writeAndFlush(responsePacket);
    }

    /**
     * 异常处理
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        cause.printStackTrace();
        ctx.close();
    }
}