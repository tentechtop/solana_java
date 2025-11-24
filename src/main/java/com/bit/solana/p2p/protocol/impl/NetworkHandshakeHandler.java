package com.bit.solana.p2p.protocol.impl;

import com.bit.solana.config.CommonConfig;
import com.bit.solana.p2p.impl.QuicNodeWrapper;
import com.bit.solana.p2p.peer.Peer;
import com.bit.solana.p2p.protocol.NetworkHandshake;
import com.bit.solana.p2p.protocol.P2PMessage;
import com.bit.solana.p2p.protocol.ProtocolEnum;
import com.bit.solana.p2p.protocol.ProtocolHandler;
import com.bit.solana.util.ECCWithAESGCM;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.Base58;
import org.bouncycastle.util.Arrays;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;

import static com.bit.solana.config.CommonConfig.*;
import static com.bit.solana.p2p.protocol.P2PMessage.newResponseMessage;
import static com.bit.solana.util.ByteUtils.bytesToHex;
import static com.bit.solana.util.ECCWithAESGCM.generateCurve25519KeyPair;

@Slf4j
@Component
public class NetworkHandshakeHandler implements ProtocolHandler.ResultProtocolHandler{

    @Autowired
    private CommonConfig commonConfig;

    @Override
    public byte[] handleResult(P2PMessage requestParams) throws Exception {
        byte[] senderId = requestParams.getSenderId();
        byte[] data = requestParams.getData();

        NetworkHandshake deserialize = NetworkHandshake.deserialize(data);
        byte[] aPublicKey = deserialize.getSharedSecret();


        byte[][] BKeys = generateCurve25519KeyPair();
        byte[] bPrivateKey = BKeys[0];
        byte[] bPublicKey = BKeys[1];

        //协商
        byte[] sharedSecret = ECCWithAESGCM.generateSharedSecret(bPrivateKey, aPublicKey);
        log.info("共享加密密钥对sharedSecret: {}", bytesToHex(sharedSecret));

        //记录并保存
        String encode = bytesToHex(senderId);
        //在线节点缓存
        Peer ifPresent = ONLINE_PEER_CACHE.getIfPresent(encode);
        if (ifPresent != null) {
            ifPresent.setSharedSecret(sharedSecret);
            ONLINE_PEER_CACHE.put(encode, ifPresent);
        }else {
            ONLINE_PEER_CACHE.put(bytesToHex(senderId), Peer.builder()
                    .id(senderId)
                    .sharedSecret(sharedSecret)
                    .isOnline(true)
                    .lastSeen(System.currentTimeMillis())
                    .build());
        }
        //连接缓存
        QuicNodeWrapper existingWrapper = PEER_CONNECT_CACHE.getIfPresent(encode);

        String hostAddress = "127.0.0.1";
        int port = 8333;
        SocketAddress remoteSocketAddress = requestParams.getQuicChannel().remoteSocketAddress();
        if (remoteSocketAddress instanceof InetSocketAddress) {
            InetSocketAddress inetAddr = (InetSocketAddress) remoteSocketAddress;
            // 获取IP地址字符串（排除方括号/端口，纯IP）
            hostAddress = inetAddr.getAddress().getHostAddress();
            port = inetAddr.getPort();
            log.info("远程IP: {}", hostAddress);
            log.info("远程端口: {}", port);
        }

        if (existingWrapper != null) {
            log.info("连接缓存已存在，更新连接信息");
            existingWrapper.setQuicChannel(requestParams.getQuicChannel());
            existingWrapper.setActive(true);
            existingWrapper.setAddress(hostAddress);
            existingWrapper.setPort(port);
            existingWrapper.updateLastSeen();
            existingWrapper.startHeartbeat(HEARTBEAT_INTERVAL,commonConfig.getSelf().getId());
            PEER_CONNECT_CACHE.put(encode, existingWrapper);
        }else {
            log.info("连接缓存不存在，新增连接信息");
            QuicNodeWrapper quicNodeWrapper = new QuicNodeWrapper(GLOBAL_SCHEDULER);
            quicNodeWrapper.setNodeId(senderId);
            quicNodeWrapper.setAddress(hostAddress);
            quicNodeWrapper.setPort(port);
            quicNodeWrapper.setQuicChannel(requestParams.getQuicChannel());
            quicNodeWrapper.setActive(true);
            quicNodeWrapper.updateLastSeen();
            quicNodeWrapper.startHeartbeat(HEARTBEAT_INTERVAL,commonConfig.getSelf().getId());
            PEER_CONNECT_CACHE.put(bytesToHex(senderId), quicNodeWrapper);
        }

        NetworkHandshake networkHandshake = new NetworkHandshake();
        networkHandshake.setNodeId(commonConfig.getSelf().getId());
        networkHandshake.setSharedSecret(bPublicKey);

        byte[] serialize = networkHandshake.serialize();
        //构建协议的响应
        P2PMessage p2PMessage = newResponseMessage(commonConfig.getSelf().getId(), ProtocolEnum.Network_handshake_V1,requestParams.getRequestId(), serialize);
        return p2PMessage.serialize();
    }
}
