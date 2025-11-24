package com.bit.solana.p2p.protocol.impl;

import com.bit.solana.p2p.impl.CommonConfig;
import com.bit.solana.p2p.protocol.NetworkHandshake;
import com.bit.solana.p2p.protocol.P2PMessage;
import com.bit.solana.p2p.protocol.ProtocolEnum;
import com.bit.solana.p2p.protocol.ProtocolHandler;
import lombok.extern.slf4j.Slf4j;
import org.checkerframework.checker.units.qual.A;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;

import static com.bit.solana.p2p.protocol.P2PMessage.newRequestMessage;
import static com.bit.solana.p2p.protocol.P2PMessage.newResponseMessage;

@Slf4j
@Component
public class NetworkHandshakeHandler implements ProtocolHandler.ResultProtocolHandler{

    @Autowired
    private CommonConfig commonConfig;

    @Override
    public byte[] handleResult(P2PMessage requestParams) throws IOException {
        NetworkHandshake networkHandshake = new NetworkHandshake();
        networkHandshake.setNodeId(commonConfig.getSelf().getId());
        byte[] serialize = networkHandshake.serialize();
        //构建协议的响应
        P2PMessage p2PMessage = newResponseMessage(commonConfig.getSelf().getId(), ProtocolEnum.Network_handshake_V1,requestParams.getRequestId(), serialize);
        boolean response = p2PMessage.isResponse();
        log.info("是响应: {}", response);

        return p2PMessage.serialize();
    }
}
