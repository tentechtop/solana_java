package com.bit.solana.p2p.impl;

import com.bit.solana.config.CommonConfig;
import com.bit.solana.p2p.peer.RoutingTable;
import com.bit.solana.p2p.protocol.P2PMessage;
import com.bit.solana.p2p.protocol.ProtocolEnum;
import com.bit.solana.p2p.quic.QuicConnection;
import com.bit.solana.p2p.quic.QuicMsg;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

import static com.bit.solana.config.CommonConfig.RESPONSE_FUTURECACHE;
import static com.bit.solana.p2p.protocol.P2PMessage.newRequestMessage;
import static com.bit.solana.p2p.quic.QuicConnectionManager.getConnection;
import static com.bit.solana.p2p.quic.QuicConnectionManager.getPeerConnection;
import static com.bit.solana.util.ByteUtils.bytesToHex;

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


    public byte[] sendData(String peerId, ProtocolEnum protocol, byte[] request, int time)
            throws IOException, InterruptedException, ExecutionException, TimeoutException {
        P2PMessage p2PMessage = newRequestMessage(commonConfig.getSelf().getId(), protocol, request);
        byte[] serialize = p2PMessage.serialize();
        CompletableFuture<QuicMsg> responseFuture = new CompletableFuture<>();
        RESPONSE_FUTURECACHE.put(bytesToHex(p2PMessage.getRequestId()), responseFuture);
        QuicConnection peerConnection = getPeerConnection(peerId);
        log.info("节点ID{}",peerId);
        assert peerConnection != null;
        peerConnection.sendData(serialize);
        return responseFuture.get(time, TimeUnit.SECONDS).getData();
    }



}
