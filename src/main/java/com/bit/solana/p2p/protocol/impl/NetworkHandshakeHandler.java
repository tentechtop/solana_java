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



        NetworkHandshake networkHandshake = new NetworkHandshake();
        networkHandshake.setNodeId(commonConfig.getSelf().getId());
        networkHandshake.setSharedSecret(bPublicKey);

        byte[] serialize = networkHandshake.serialize();
        //构建协议的响应
        P2PMessage p2PMessage = newResponseMessage(commonConfig.getSelf().getId(), ProtocolEnum.Network_handshake_V1,requestParams.getRequestId(), serialize);
        return p2PMessage.serialize();
    }
}
