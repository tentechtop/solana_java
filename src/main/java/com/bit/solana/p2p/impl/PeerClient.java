package com.bit.solana.p2p.impl;

import com.bit.solana.config.CommonConfig;
import com.bit.solana.p2p.impl.handle.QuicConnHandler;
import com.bit.solana.p2p.impl.handle.QuicStreamHandler;
import com.bit.solana.p2p.peer.RoutingTable;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.locks.ReentrantLock;

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
    private QuicStreamHandler quicStreamHandler;
    @Autowired
    private PeerServiceImpl peerService;


    // 全局锁（避免重复连接）
    private final ReentrantLock connectLock = new ReentrantLock();


    private NioEventLoopGroup eventLoopGroup;


    private Bootstrap bootstrap;

    private Channel channel;

    @PostConstruct
    public void init() throws InterruptedException, ExecutionException {
        eventLoopGroup = new NioEventLoopGroup(Runtime.getRuntime().availableProcessors());
        bootstrap = new Bootstrap();
        channel = peerService.getQuicServerChannel();
        log.info("PeerClient 初始化完成，绑定UDP端口:{}", channel.localAddress());
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
        if (channel != null) {
            channel.close().syncUninterruptibly();
        }
        if (eventLoopGroup != null) {
            eventLoopGroup.shutdownGracefully();
        }
    }
}
