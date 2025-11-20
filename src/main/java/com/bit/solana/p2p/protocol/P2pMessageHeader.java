package com.bit.solana.p2p.protocol;

import lombok.Data;

@Data
public class P2pMessageHeader {
    private byte[] type;       // 消息类型（心跳/业务/响应）2字节
    private int length;        // 消息体长度 4字节
    private String requestId;  // 请求ID（用于响应匹配）
    private byte version = 0x01; // 协议版本 1字节



    // 心跳消息
    public static final byte[] HEARTBEAT_SIGNAL = new byte[]{0x00, 0x01};
    // 业务数据消息标识
    public static final byte[] BUSINESS_DATA_SIGNAL = new byte[]{0x00, 0x02};



}
