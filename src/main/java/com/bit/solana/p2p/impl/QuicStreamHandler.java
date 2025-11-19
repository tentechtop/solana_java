package com.bit.solana.p2p.impl;

import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.incubator.codec.quic.QuicChannel;
import io.netty.incubator.codec.quic.QuicConnectionAddress;
import io.netty.incubator.codec.quic.QuicStreamAddress;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import lombok.extern.slf4j.Slf4j;

import static com.bit.solana.util.ByteUtils.bytesToHex;


/**
 * 处理QUIC连接的处理器
 */
@Slf4j
public class QuicStreamHandler extends SimpleChannelInboundHandler<ByteBuf> {

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) throws Exception {
        // 这里的msg才是真正的ByteBuf数据
        log.info("收到数据: {}", msg);

    }




    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        Channel channel = ctx.channel();

        log.info("新的QUIC连接建立: {}", channel);

    }



    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        Channel channel = ctx.channel();


        log.info("QUIC连接关闭: {}", channel);

    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("QUIC连接异常", cause);
        ctx.close();
    }

    @Override
    public boolean isSharable() {
        return true;
    }



}
