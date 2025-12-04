package com.bit.solana.p2p.impl.handle;

import com.bit.solana.config.CommonConfig;
import com.bit.solana.p2p.protocol.P2PMessage;
import com.bit.solana.p2p.protocol.ProtocolEnum;
import com.bit.solana.p2p.protocol.ProtocolHandler;
import com.bit.solana.p2p.protocol.ProtocolRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static com.bit.solana.config.CommonConfig.RESPONSE_FUTURECACHE;
import static com.bit.solana.util.ByteUtils.bytesToHex;


/**
 * 流处理
 */
@Slf4j
@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
@ChannelHandler.Sharable
public class QuicStreamHandler extends SimpleChannelInboundHandler<ByteBuf> {
    //    CompletableFuture<byte[]> removedFuture = RESPONSE_FUTURECACHE.asMap().remove(p2PMessage.getRequestId());

    @Autowired
    private ProtocolRegistry protocolRegistry;
    @Autowired
    private CommonConfig commonConfig;



    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) throws Exception {
        log.info("收到数据: {}", msg);


    }
}
