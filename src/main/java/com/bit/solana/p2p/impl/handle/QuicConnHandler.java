package com.bit.solana.p2p.impl.handle;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.incubator.codec.quic.QuicChannel;
import io.netty.incubator.codec.quic.QuicConnectionAddress;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

@Slf4j
@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
@ChannelHandler.Sharable
public class QuicConnHandler extends SimpleChannelInboundHandler<ByteBuf> {
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) throws Exception {
        log.info("连接处理收到数据: {}", msg);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        //远程地址
        log.info("连接处理: {} {}", ctx.channel().remoteAddress(), ctx.channel().id());
        QuicChannel quicChannel = (QuicChannel) ctx.channel();
        SocketAddress socketAddress = quicChannel.remoteSocketAddress();
        log.info("远程地址: {}", socketAddress);
        SocketAddress localSocketAddress = quicChannel.localSocketAddress();
        log.info("本地地址: {}", localSocketAddress);

        //保存这个节点



    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        log.info("QUIC 连接关闭: {}", ctx.channel().id());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        log.error("QUIC 连接异常", cause);
        ctx.close();
    }



}
