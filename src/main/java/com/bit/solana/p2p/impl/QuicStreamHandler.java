package com.bit.solana.p2p.impl;

import com.bit.solana.p2p.protocol.P2pMessageHeader;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.incubator.codec.quic.QuicChannel;
import io.netty.incubator.codec.quic.QuicConnectionAddress;
import io.netty.incubator.codec.quic.QuicStreamAddress;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

import static com.bit.solana.p2p.protocol.P2pMessageHeader.HEARTBEAT_SIGNAL;
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


        // ========== 修正：获取QUIC连接的IP/端口 ==========
        Channel channel = ctx.channel();
        InetSocketAddress remoteAddress = getQuicRemoteInetAddress(channel);
        InetSocketAddress localAddress = getQuicLocalInetAddress(channel);

        log.info("QUIC连接信息 | 源IP: {} | 源端口: {} | 本地IP: {} | 本地端口: {}",
                remoteAddress != null ? remoteAddress.getAddress().getHostAddress() : "未知",
                remoteAddress != null ? remoteAddress.getPort() : "未知",
                localAddress != null ? localAddress.getAddress().getHostAddress() : "未知",
                localAddress != null ? localAddress.getPort() : "未知");

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
        Channel channel = ctx.channel();
        int readableBytes = msg.readableBytes();
        log.info("收到QUIC业务数据 | 连接：{} | 数据长度：{} bytes | 数据内容：{}",
                channel, readableBytes, bytesToHex(msg.array()));

        // ========== 替换为实际业务逻辑 ==========
        // 示例：解析P2P消息头、处理业务数据等
        // P2pMessageHeader header = parseHeader(msg);
        // handleBusinessData(ctx, header, msg);
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
