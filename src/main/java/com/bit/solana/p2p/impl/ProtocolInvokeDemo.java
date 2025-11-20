package com.bit.solana.p2p.impl;

import com.bit.solana.p2p.protocol.ProtocolEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;

@Slf4j
@Component
public class ProtocolInvokeDemo {
    @Autowired
    private PeerClient peerClient;

    /**
     * 调用区块查询协议（有返回值）
     */
    public void invokeBlockProtocol() {
        try {
            // 1. 构建protobuf请求参数（此处模拟为二进制）
            byte[] requestParams = "区块ID-123456".getBytes();
            // 2. 调用有返回值协议
            InetSocketAddress inetSocketAddress = new InetSocketAddress("127.0.0.1", 8080);

            byte[] response = peerClient.invokeWithReturn("1", ProtocolEnum.BLOCK_V1, requestParams);
            // 3. 处理响应（protobuf反序列化）
            log.info("区块查询响应：{}", new String(response));
        } catch (Exception e) {
            log.error("区块查询调用失败", e);
        }
    }

    /**
     * 调用交易提交协议（无返回值）
     */
    public void invokeTxProtocol() {
        // 1. 构建protobuf请求参数（此处模拟为二进制）
        byte[] requestParams = "交易数据-7890".getBytes();
        // 2. 调用无返回值协议
        peerClient.invokeWithoutReturn("目标节点ID", ProtocolEnum.TX_V1, requestParams);
    }
}
