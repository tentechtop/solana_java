package com.bit.solana.p2p.protocol;

/**
 * 无返回值的协议处理器
 */
@FunctionalInterface
public interface VoidProtocolHandler extends BaseProtocolHandler {
    /**
     * 处理请求（无返回值）
     * @param requestParams protobuf序列化的请求参数
     */
    void handle(byte[] requestParams);
}