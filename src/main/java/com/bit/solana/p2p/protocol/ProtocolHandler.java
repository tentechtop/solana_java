package com.bit.solana.p2p.protocol;

/**
 * 协议处理器函数式接口（适配P2P场景，入参/返回值均为字节数组）
 */
@FunctionalInterface
public interface ProtocolHandler {
    /**
     * 处理协议请求
     * @param requestParams 请求参数（protobuf反序列化后的字节数组）
     * @return 处理结果（有返回值则返回字节数组，无返回值返回null）
     */
    byte[] handle(byte[] requestParams);
}