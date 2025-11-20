package com.bit.solana.p2p.protocol;

import lombok.Getter;

/**
 * 协议枚举（4字节int编码）
 */
@Getter
public enum ProtocolEnum {
    // 区块查询协议 /block/1.0.0 (code=1)
    BLOCK_V1(1, "/block/1.0.0", "1.0.0"),
    // 交易提交协议 /tx/1.0.0 (code=2)
    TX_V1(2, "/tx/1.0.0", "1.0.0"),

    CHAIN_V1(3, "/CHAIN/1.0.0", "1.0.0"),

    // 心跳协议（内置） /heartbeat/1.0.0 (code=0)
    HEARTBEAT_V1(0, "/heartbeat/1.0.0", "1.0.0");

    // 4字节int协议编码（网络字节序）
    private final int code;
    // 协议路径
    private final String path;
    // 协议版本
    private final String version;

    ProtocolEnum(int code, String path, String version) {
        this.code = code;
        this.path = path;
        this.version = version;
    }

    /**
     * 通过code反向查找枚举
     */
    public static ProtocolEnum getByCode(int code) {
        for (ProtocolEnum protocol : values()) {
            if (protocol.getCode() == code) {
                return protocol;
            }
        }
        throw new IllegalArgumentException("未知协议编码: " + code);
    }
}