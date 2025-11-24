package com.bit.solana.p2p.impl.handle;

import com.bit.solana.config.CommonConfig;
import com.bit.solana.p2p.protocol.P2PMessage;
import com.bit.solana.p2p.protocol.ProtocolEnum;
import com.bit.solana.p2p.protocol.ProtocolHandler;
import com.bit.solana.p2p.protocol.ProtocolRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

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
        log.info("流处理收到数据: {}", msg);
        byte[] data = new byte[msg.readableBytes()];
        msg.readBytes(data);
        P2PMessage deserialize = P2PMessage.deserialize(data);
        //是请求还是响应
        if (deserialize.isRequest()) {
            log.info("收到请求: {}", deserialize);
            Map<ProtocolEnum, ProtocolHandler> handlerMap = protocolRegistry.getHandlerMap();
            ProtocolHandler protocolHandler = handlerMap.get(ProtocolEnum.fromCode(deserialize.getType()));
            if (protocolHandler != null){
                byte[] handle = protocolHandler.handle(deserialize);
                if (handle != null){
                    //用原来的流写回
                    //包装型 ByteBuf 无需释放的底层逻辑
                    //Unpooled.wrappedBuffer(handle) 创建的 UnpooledHeapByteBuf 有两个关键特性：
                    //零拷贝：缓冲区不持有新内存，只是对外部 handle 字节数组的 “视图”；
                    //引用计数无意义：其 release() 方法仅修改引用计数，但不会释放任何内存（因为内存是外部的 byte[]，由 JVM 垃圾回收管理）。
                    ByteBuf byteBuf = Unpooled.wrappedBuffer(handle);
                    ctx.writeAndFlush(byteBuf);
                }
            }else {
                log.info("未注册的协议：{}", deserialize.getType());
            }
        }else if (deserialize.isResponse()) {
            log.info("收到响应: {}", deserialize);
            CompletableFuture<byte[]> ifPresent = RESPONSE_FUTURECACHE.asMap().remove(bytesToHex(deserialize.getRequestId()));
            if (ifPresent != null) {
                ifPresent.complete(data);
            }
        }else {
            log.info("收到普通消息: {}", deserialize);
        }





    }
}
