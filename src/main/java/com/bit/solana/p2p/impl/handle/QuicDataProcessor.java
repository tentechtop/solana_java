package com.bit.solana.p2p.impl.handle;

import com.bit.solana.p2p.impl.QuicNodeWrapper;
import com.bit.solana.p2p.protocol.P2PMessage;
import com.bit.solana.p2p.protocol.ProtocolEnum;
import com.bit.solana.p2p.protocol.ProtocolHandler;
import com.bit.solana.p2p.protocol.ProtocolRegistry;
import com.bit.solana.p2p.quic.QuicConnection;
import com.bit.solana.p2p.quic.QuicConstants;
import com.bit.solana.p2p.quic.QuicMsg;
import com.google.protobuf.InvalidProtocolBufferException;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;

import static com.bit.solana.config.CommonConfig.RESPONSE_FUTURECACHE;
import static com.bit.solana.p2p.quic.QuicConnectionManager.PeerConnect;
import static com.bit.solana.p2p.quic.QuicConnectionManager.getConnection;
import static com.bit.solana.util.ByteUtils.bytesToHex;

@Slf4j
@Component
public class QuicDataProcessor {

    @Autowired
    private ProtocolRegistry protocolRegistry;




    /**
     * 处理单条消息的具体业务逻辑（替换为你的真实业务）
     * @param msg 单条Quic消息
     */
    public void processSingleMsg(QuicMsg msg) {
        try {
            // 1. 解析二进制消息（如protobuf反序列化）
            // 2. 业务校验（如签名、长度校验）
            // 3. 数据入库/转发/计算等

            //回复节点  通过节点ID找到连接 并发送信息  将消息分发到对应的处理器处理即可 处理器如果有返回值 就调用协议返回即可

            P2PMessage deserialize = P2PMessage.deserialize(msg.getData());
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
                        QuicConnection connection = getConnection(msg.getConnectionId());
                        connection.sendData(handle);
                    }
                }else {
                    log.info("未注册的协议：{}", deserialize.getType());
                }
            }else if (deserialize.isResponse()) {
                log.info("收到响应: {}", deserialize);
                CompletableFuture<QuicMsg> ifPresent = RESPONSE_FUTURECACHE.asMap().remove(bytesToHex(deserialize.getRequestId()));
                if (ifPresent != null) {
                    ifPresent.complete(msg);
                }
            }else {
                log.info("收到普通消息: {}", deserialize);
            }
        }catch (InvalidProtocolBufferException e){
            log.error("解析失败");
        }catch (Exception e){
            log.error("消息失败（长度：{}字节）" , msg.getData().length, e);
        }
    }



}