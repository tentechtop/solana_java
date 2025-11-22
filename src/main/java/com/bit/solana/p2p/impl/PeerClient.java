package com.bit.solana.p2p.impl;

import com.bit.solana.p2p.impl.handle.QuicConnHandler;
import com.bit.solana.p2p.impl.handle.QuicStreamHandler;
import com.bit.solana.p2p.peer.Peer;
import com.bit.solana.p2p.peer.RoutingTable;
import com.bit.solana.p2p.protocol.ProtocolRegistry;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.incubator.codec.quic.*;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static com.bit.solana.p2p.impl.Common.*;

@Slf4j
@Component
public class PeerClient {

    @Autowired
    private ProtocolRegistry protocolRegistry;
    @Autowired
    private RoutingTable routingTable;
    @Autowired
    private QuicConnHandler quicConnHandler;
    @Autowired
    private QuicStreamHandler quicStreamHandler;
    // 全局锁（避免重复连接）
    private final ReentrantLock connectLock = new ReentrantLock();


    private NioEventLoopGroup eventLoopGroup;
    private Bootstrap bootstrap;
    private QuicSslContext sslContext;
    private ChannelHandler codec;
    private Channel datagramChannel;

    @PostConstruct
    public void init() throws InterruptedException, ExecutionException {
        eventLoopGroup = new NioEventLoopGroup(Runtime.getRuntime().availableProcessors());
        sslContext = QuicSslContextBuilder.forClient()
                .trustManager(InsecureTrustManagerFactory.INSTANCE)
                .applicationProtocols("solana-p2p")
                .build();
        codec = new QuicClientCodecBuilder()
                .sslContext(sslContext)
                .maxIdleTimeout(CONNECT_KEEP_ALIVE_SECONDS, TimeUnit.SECONDS) // 延长空闲超时，适配持续发送
                .initialMaxData(50 * 1024 * 1024)
                .initialMaxStreamDataBidirectionalLocal(5 * 1024 * 1024)
                .initialMaxStreamDataBidirectionalRemote(5 * 1024 * 1024)
                .initialMaxStreamsBidirectional(200)
                .build();
        bootstrap = new Bootstrap();
        datagramChannel = bootstrap.group(eventLoopGroup)
                .channel(NioDatagramChannel.class)
                .option(ChannelOption.SO_REUSEADDR, true) // 复用端口
                .option(ChannelOption.SO_RCVBUF, 1024 * 1024) // 接收缓冲区 1MB
                .option(ChannelOption.SO_SNDBUF, 1024 * 1024) // 发送缓冲区 1MB
                .handler(codec)
                .bind(0) // 随机绑定可用端口
                .sync()
                .channel();
        log.info("PeerClient 初始化完成，绑定UDP端口:{}", datagramChannel.localAddress());
    }


    /**
     * 连接节点
     */
    public QuicNodeWrapper connect(byte[] nodeId) throws ExecutionException, InterruptedException {
        QuicNodeWrapper existingWrapper = PEER_CONNECT_CACHE.getIfPresent(nodeId);
        if (existingWrapper != null && existingWrapper.isActive()) {
            existingWrapper.setLastSeen(System.currentTimeMillis());
            log.debug("节点{}已处于活跃连接状态，直接返回", nodeId);
            return existingWrapper;
        }
        QuicChannel quicChannel = null;
        QuicStreamChannel heartbeatStream = null;
        Peer node = routingTable.getNode(nodeId);
        if (node == null) {
            return null;
        }
        InetSocketAddress remoteAddress = node.getInetSocketAddress();
        if (remoteAddress == null) {
            return null;
        }
        quicChannel = QuicChannel.newBootstrap(datagramChannel)
                .handler(quicConnHandler)
                .streamHandler(quicStreamHandler)
                .remoteAddress(remoteAddress)
                .connect()
                .get();

        heartbeatStream = quicChannel.createStream(QuicStreamType.BIDIRECTIONAL,
                quicStreamHandler).sync().getNow();
        QuicNodeWrapper quicNodeWrapper = new QuicNodeWrapper(GLOBAL_SCHEDULER);
        quicNodeWrapper.setNodeId(nodeId);
        quicNodeWrapper.setQuicChannel(quicChannel);
        quicNodeWrapper.setHeartbeatStream(heartbeatStream);
        quicNodeWrapper.setAddress(node.getAddress());
        quicNodeWrapper.setPort(node.getPort());
        quicNodeWrapper.setOutbound(true);//主动出站
        quicNodeWrapper.setActive(true);
        quicNodeWrapper.setLastSeen(System.currentTimeMillis());
        PEER_CONNECT_CACHE.put(nodeId, quicNodeWrapper);
        quicNodeWrapper.startHeartbeat(HEARTBEAT_INTERVAL);
        return quicNodeWrapper;
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
