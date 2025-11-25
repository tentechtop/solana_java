package com.bit.solana.p2p.impl.handle;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.incubator.codec.quic.QuicChannel;
import lombok.extern.slf4j.Slf4j;

import java.net.SocketAddress;

@Slf4j
public class StunHandler extends SimpleChannelInboundHandler<ByteBuf> {
    // 标记是否为STUN服务端（区分处理逻辑）
    private final boolean isStunServer;

    public StunHandler(boolean isStunServer) {
        this.isStunServer = isStunServer;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) throws Exception {
        if (isStunServer){
            //拿到请求并返回
            log.info("服务端收到 连接处理收到数据: {}", msg);
            //拿到客户端地址
            SocketAddress remoteAddress = ctx.channel().remoteAddress();
            log.info("服务端 拿到客户端地址: {}", remoteAddress);
        }else {
            //读数据
            log.info("客户端收到 连接处理收到数据: {}", msg);
        }
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        //远程地址

        if (isStunServer){
            //拿到请求并返回
            log.info("服务端 收到客户端的连接处理: {} {}", ctx.channel().remoteAddress(), ctx.channel().id());
        }else {
            //读数据
            log.info("客户端 收到服务端的连接处理: {} {}", ctx.channel().remoteAddress(), ctx.channel().id());
        }




    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        log.info("STUN连接关闭: {}", ctx.channel().id());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        log.error("STUN 连接异常", cause);
        ctx.close();
    }

}
