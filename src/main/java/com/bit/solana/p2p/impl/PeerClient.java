package com.bit.solana.p2p.impl;

import com.bit.solana.config.CommonConfig;
import com.bit.solana.p2p.peer.RoutingTable;
import com.bit.solana.p2p.protocol.ProtocolEnum;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Component
public class PeerClient {

    @Autowired
    private CommonConfig commonConfig;

    @Autowired
    private RoutingTable routingTable;
    @Autowired
    private PeerServiceImpl peerService;


    // 全局锁（避免重复连接）
    private final ReentrantLock connectLock = new ReentrantLock();


    private NioEventLoopGroup eventLoopGroup;


    private Bootstrap bootstrap;

    private Channel channel;



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

    public byte[] sendData(byte[] decode, ProtocolEnum protocolEnum, byte[] bytes, int i) {

        return null;
    }
}
