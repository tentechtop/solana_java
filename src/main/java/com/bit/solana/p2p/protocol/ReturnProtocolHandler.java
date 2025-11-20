package com.bit.solana.p2p.protocol;

import java.io.IOException;

/**
 * 有返回值的协议处理器
 */
@FunctionalInterface
public interface ReturnProtocolHandler extends BaseProtocolHandler {
    /**
     * 处理请求并返回响应（protobuf二进制）
     * @param requestParams protobuf序列化的请求参数
     * @return protobuf序列化的响应结果
     */
    byte[] handle(byte[] requestParams) throws IOException;
}
