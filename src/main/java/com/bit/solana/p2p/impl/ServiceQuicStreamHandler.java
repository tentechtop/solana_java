package com.bit.solana.p2p.impl;

import com.bit.solana.p2p.protocol.ProtocolEnum;
import com.bit.solana.p2p.protocol.ProtocolPacketCodec;
import com.bit.solana.p2p.protocol.ProtocolRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.incubator.codec.quic.QuicChannel;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static com.bit.solana.p2p.impl.PeerClient.responseFutureCache;
import static com.bit.solana.p2p.protocol.P2pMessageHeader.HEARTBEAT_SIGNAL;
import static com.bit.solana.util.ByteUtils.bytesToHex;


/**
 * 处理QUIC连接的处理器
 */
@Slf4j
@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class ServiceQuicStreamHandler extends SimpleChannelInboundHandler<ByteBuf> {
    // 注入协议注册器（Spring管理）
    @Autowired
    private ProtocolRegistry protocolRegistry;



    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) throws Exception {
        // 这里的msg才是真正的ByteBuf数据
        log.info("收到数据: {}", msg);
        // 1. 基础校验：数据长度不足心跳标识长度，直接判定非心跳
        if (msg.readableBytes() < HEARTBEAT_SIGNAL.length) {
            handleNonHeartbeatData(ctx, msg);
            return;
        }

        // 2. 标记当前读指针位置，便于重置（避免消费数据）
        msg.markReaderIndex();

        // 3. 读取与心跳标识长度一致的字节进行对比
        byte[] receivedBytes = new byte[HEARTBEAT_SIGNAL.length];
        msg.readBytes(receivedBytes);

        // 4. 判断是否为心跳消息
        boolean isHeartbeat = isHeartbeatSignal(receivedBytes);
        if (isHeartbeat) {
            handleHeartbeat(ctx, msg);
        } else {
            // 非心跳：重置读指针，交给业务逻辑处理
            msg.resetReaderIndex();
            handleNonHeartbeatData(ctx, msg);
        }

    }

    // ========== 核心工具方法：获取QUIC连接的InetSocketAddress ==========
    /**
     * 获取QUIC流通道对应的远程IP和端口
     */
    private InetSocketAddress getQuicRemoteInetAddress(Channel channel) {
        if (!(channel instanceof QuicStreamChannel)) {
            return channel.remoteAddress() instanceof InetSocketAddress ?
                    (InetSocketAddress) channel.remoteAddress() : null;
        }

        // 从QuicStreamChannel获取QuicChannel（连接层）
        QuicStreamChannel quicStreamChannel = (QuicStreamChannel) channel;
        QuicChannel quicChannel = quicStreamChannel.parent();
        if (quicChannel == null) {
            return null;
        }

        // 从QuicChannel获取真正的远程地址（InetSocketAddress）
        SocketAddress remoteAddress = quicChannel.remoteAddress();
        return remoteAddress instanceof InetSocketAddress ?
                (InetSocketAddress) remoteAddress : null;
    }

    /**
     * 获取QUIC流通道对应的本地IP和端口
     */
    private InetSocketAddress getQuicLocalInetAddress(Channel channel) {
        if (!(channel instanceof QuicStreamChannel)) {
            return channel.localAddress() instanceof InetSocketAddress ?
                    (InetSocketAddress) channel.localAddress() : null;
        }

        QuicStreamChannel quicStreamChannel = (QuicStreamChannel) channel;
        QuicChannel quicChannel = quicStreamChannel.parent();
        if (quicChannel == null) {
            return null;
        }

        SocketAddress localAddress = quicChannel.localAddress();
        return localAddress instanceof InetSocketAddress ?
                (InetSocketAddress) localAddress : null;
    }

    /**
     * 处理非心跳业务数据
     */
    private void handleNonHeartbeatData(ChannelHandlerContext ctx, ByteBuf msg) {
        log.info("收到QUIC业务数据");
        try {
            // 1. 读取完整数据包
            byte[] data = new byte[msg.readableBytes()];
            msg.readBytes(data);


            // 1. 先解析数据包类型
            ProtocolPacketCodec.PacketType packetType = ProtocolPacketCodec.parsePacketType(data);
            if (packetType == ProtocolPacketCodec.PacketType.REQUEST) {
                // 处理请求包（原有逻辑）
                handleRequestPacket(ctx, data);
            } else if (packetType == ProtocolPacketCodec.PacketType.RESPONSE) {
                // 处理响应包（匹配客户端回调）
                handleResponsePacket(ctx,data);
            }
        } catch (Exception e) {
            log.error("QUIC流处理请求失败", e);
            ctx.close();
        }
    }

    // 处理请求包（原有逻辑封装）
    private void handleRequestPacket(ChannelHandlerContext ctx, byte[] data) {
        ProtocolPacketCodec.RequestPacket requestPacket = ProtocolPacketCodec.parseRequest(data);
        ProtocolEnum protocol = requestPacket.getProtocol();
        UUID requestId = requestPacket.getRequestId();
        byte[] params = requestPacket.getParams();
        boolean hasReturn = requestPacket.isHasReturn();

        log.info("收到协议请求：{}，请求ID：{}，参数长度：{}字节",
                protocol.getPath(), requestId, params.length);

        try {
            byte[] response = protocolRegistry.handleRequest(protocol, params);
            if (hasReturn && response != null) {
                log.info("发送响应，请求ID：{}，响应长度：{}字节", requestId, response.length);
                byte[] responseData = ProtocolPacketCodec.buildResponse(protocol, requestId, response);
                ByteBuf respBuf = ctx.alloc().buffer().writeBytes(responseData);
                ctx.writeAndFlush(respBuf)
                        .addListener(future -> {
                            if (future.isSuccess()) {
                                log.debug("协议{}响应发送成功，请求ID：{}", protocol.getPath(), requestId);
                            } else {
                                log.error("协议{}响应发送失败，请求ID：{}", protocol.getPath(), requestId, future.cause());
                            }
                        });
            }
        } catch (Exception e) {
            log.error("处理协议请求失败，请求ID：{}", requestId, e);
        }
    }

    // 处理响应包（匹配客户端缓存的请求回调）
    private void handleResponsePacket(ChannelHandlerContext ctx,byte[] data) {
        ProtocolPacketCodec.ResponsePacket responsePacket = ProtocolPacketCodec.parseResponse(data);
        UUID requestId = responsePacket.getRequestId();
        byte[] responseData = responsePacket.getResponse();
        ProtocolEnum protocol = responsePacket.getProtocol();

        log.info("收到协议响应：{}，请求ID：{}，响应长度：{}字节",
                protocol.getPath(), requestId, responseData.length);

        //写回
        handleResponse(responseData);
    }

    /**
     * 处理响应数据包（供QuicStreamHandler调用）
     */
    public void handleResponse(byte[] responseData) {
        try {
            ProtocolPacketCodec.ResponsePacket responsePacket = ProtocolPacketCodec.parseResponse(responseData);
            UUID requestId = responsePacket.getRequestId();
            byte[] response = responsePacket.getResponse();
            // 唤醒等待的Future并移除缓存
            CompletableFuture<byte[]> future = responseFutureCache.remove(requestId); // 关键：使用remove而非get
            if (future != null) {
                future.complete(response);
                log.debug("收到协议响应，请求ID：{}，响应长度：{}字节", requestId, response.length);
            } else {
                log.warn("收到未知请求ID的响应：{}", requestId);
            }
        } catch (Exception e) {
            log.error("处理响应数据包失败", e);
        }
    }

    /**
     * 处理心跳消息
     */
    private void handleHeartbeat(ChannelHandlerContext ctx, ByteBuf msg) {
        Channel channel = ctx.channel();
        log.info("收到QUIC心跳消息 | 连接：{} | 心跳标识：{}",
                channel, bytesToHex(HEARTBEAT_SIGNAL));

        // 可选：回复心跳（若需要双向心跳）
        // writeHeartbeatResponse(ctx);

        // 可选：更新连接活跃时间（用于超时清理）
        // updateConnectionActiveTime(channel);
    }

    /**
     * 校验字节数组是否匹配心跳标识
     */
    private boolean isHeartbeatSignal(byte[] receivedBytes) {
        if (receivedBytes.length != HEARTBEAT_SIGNAL.length) {
            return false;
        }
        for (int i = 0; i < HEARTBEAT_SIGNAL.length; i++) {
            if (receivedBytes[i] != HEARTBEAT_SIGNAL[i]) {
                return false;
            }
        }
        return true;
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
