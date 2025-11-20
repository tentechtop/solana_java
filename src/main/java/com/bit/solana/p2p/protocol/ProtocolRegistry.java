package com.bit.solana.p2p.protocol;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 协议注册器（单例）
 */
@Slf4j
@Component
public class ProtocolRegistry {
    // 协议枚举 -> 处理器映射
    private final Map<ProtocolEnum, BaseProtocolHandler> handlerMap = new ConcurrentHashMap<>();

    /**
     * 注册协议处理器
     * @param protocol 协议枚举
     * @param handler 处理器（自动识别有/无返回值）
     */
    public void register(ProtocolEnum protocol, BaseProtocolHandler handler) {
        if (handlerMap.containsKey(protocol)) {
            log.warn("协议{}已注册处理器，将覆盖", protocol.getPath());
        }
        handlerMap.put(protocol, handler);
        log.info("协议{}注册成功，处理器类型:{}",
                protocol.getPath(), handler.getClass().getSimpleName());
    }

    /**
     * 处理请求（核心路由方法）
     * @param protocol 协议枚举
     * @param requestParams 请求参数（protobuf二进制）
     * @return 响应结果（有返回值处理器）/null（无返回值处理器）
     */
    public byte[] handleRequest(ProtocolEnum protocol, byte[] requestParams) {
        BaseProtocolHandler handler = handlerMap.get(protocol);
        if (handler == null) {
            throw new IllegalArgumentException("协议" + protocol.getPath() + "未注册处理器");
        }

        // 分类型处理
        if (handler instanceof ReturnProtocolHandler) {
            try {
                return ((ReturnProtocolHandler) handler).handle(requestParams);
            } catch (Exception e) {
                log.error("有返回值处理器执行失败:{}", protocol.getPath(), e);
                throw new RuntimeException("处理器执行失败", e);
            }
        } else if (handler instanceof VoidProtocolHandler) {
            try {
                ((VoidProtocolHandler) handler).handle(requestParams);
                return null;
            } catch (Exception e) {
                log.error("无返回值处理器执行失败:{}", protocol.getPath(), e);
                throw new RuntimeException("处理器执行失败", e);
            }
        } else {
            throw new IllegalArgumentException("处理器类型不支持: " + handler.getClass().getName());
        }
    }

    /**
     * 判断协议是否有返回值
     */
    public boolean hasReturn(ProtocolEnum protocol) {
        BaseProtocolHandler handler = handlerMap.get(protocol);
        if (handler == null) {
            return false;
        }
        return handler instanceof ReturnProtocolHandler;
    }
}