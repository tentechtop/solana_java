package com.bit.solana.p2p.impl;

import com.bit.solana.config.CommonConfig;
import com.bit.solana.p2p.impl.handle.QuicConnHandler;
import com.bit.solana.p2p.impl.handle.QuicStreamHandler;
import com.bit.solana.p2p.peer.Peer;
import com.bit.solana.p2p.peer.RoutingTable;
import com.bit.solana.p2p.protocol.NetworkHandshake;
import com.bit.solana.p2p.protocol.P2PMessage;
import com.bit.solana.p2p.protocol.ProtocolEnum;
import com.bit.solana.util.MultiAddress;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;

import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.Base58;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

import static com.bit.solana.config.CommonConfig.*;
import static com.bit.solana.p2p.protocol.P2PMessage.newRequestMessage;
import static com.bit.solana.util.ByteUtils.bytesToHex;
import static com.bit.solana.util.ECCWithAESGCM.generateCurve25519KeyPair;
import static com.bit.solana.util.ECCWithAESGCM.generateSharedSecret;

//BIDIRECTIONAL 双向流 所有核心场景（心跳、带响应请求、连接维护）
//UNIDIRECTIONAL 单向流 纯广播 / 日志推送（无需回复）
//        // 单向流创建示例（按需使用）
//        //QuicStreamChannel unidirectionalStream = wrapper.getQuicChannel().createStream(QuicStreamType.UNIDIRECTIONAL, quicStreamHandler).sync().getNow();
@Slf4j
@Component
public class PeerClient {

    @Autowired
    private CommonConfig commonConfig;

    @Autowired
    private RoutingTable routingTable;
    @Autowired
    private QuicConnHandler quicConnHandler;
    @Autowired
    private QuicStreamHandler quicStreamHandler;
    @Autowired
    private PeerServiceImpl peerService;


    // 全局锁（避免重复连接）
    private final ReentrantLock connectLock = new ReentrantLock();


    private NioEventLoopGroup eventLoopGroup;


    private Bootstrap bootstrap;

    private Channel datagramChannel;

    @PostConstruct
    public void init() throws InterruptedException, ExecutionException {
        eventLoopGroup = new NioEventLoopGroup(Runtime.getRuntime().availableProcessors());
        bootstrap = new Bootstrap();
        datagramChannel = bootstrap.group(eventLoopGroup)
                .channel(NioDatagramChannel.class)
                .option(ChannelOption.SO_REUSEADDR, true)
                .option(ChannelOption.SO_RCVBUF, 10 * 1024 * 1024)
                .option(ChannelOption.SO_SNDBUF, 10 * 1024 * 1024)
                .bind(0) // 随机绑定可用端口
                .sync()
                .channel();

        log.info("PeerClient 初始化完成，绑定UDP端口:{}", datagramChannel.localAddress());
    }
















    public void disconnect(byte[] nodeId) {


    }

    /**
     * 重连
     * @param nodeId
     */
    private void reconnect(byte[] nodeId) {


    }


    @PreDestroy
    public void shutdown() throws InterruptedException {
        if (datagramChannel != null) {
            datagramChannel.close().syncUninterruptibly();
        }
        if (eventLoopGroup != null) {
            eventLoopGroup.shutdownGracefully();
        }
    }
}
