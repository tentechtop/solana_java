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

    /**
     * 无返回值的处理器（简化Lambda使用）
     */
    @FunctionalInterface
    interface VoidProtocolHandler extends ProtocolHandler {
        void handleVoid(byte[] requestParams);

        @Override
        default byte[] handle(byte[] requestParams) {
            handleVoid(requestParams);
            return null; // 强制返回null，符合无返回值语义
        }
    }

    /**
     * 有返回值的处理器（强制非null）
     */
    @FunctionalInterface
    interface ResultProtocolHandler extends ProtocolHandler {
        byte[] handleResult(byte[] requestParams);

        @Override
        default byte[] handle(byte[] requestParams) {
            byte[] result = handleResult(requestParams);
            if (result == null) {
                throw new IllegalStateException("有返回值的协议处理器不能返回null");
            }
            return result;
        }
    }
}