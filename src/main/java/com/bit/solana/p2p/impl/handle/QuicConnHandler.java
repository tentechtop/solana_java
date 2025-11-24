package com.bit.solana.p2p.impl.handle;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
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

        SocketAddress socketAddress = (QuicConnectionAddress)ctx.channel().remoteAddress();


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


    /**
     * 通用工具方法：将 SocketAddress 转换为 InetSocketAddress（处理空指针和非IP地址场景）
     * @param socketAddress 原始地址对象
     * @return 转换后的 InetSocketAddress，非IP地址则返回 null
     */
    private InetSocketAddress getInetSocketAddress(SocketAddress socketAddress) {
        if (socketAddress == null) {
            return null;
        }
        if (socketAddress instanceof InetSocketAddress) {
            return (InetSocketAddress) socketAddress;
        }
        return null;
    }
}
