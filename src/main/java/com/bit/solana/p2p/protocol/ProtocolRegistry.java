package com.bit.solana.p2p.protocol;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 协议注册表：核心管理枚举与处理器的绑定关系
 */
@Slf4j
@Component
public class ProtocolRegistry {
    // 单例模式（P2P节点全局唯一）
    private static final ProtocolRegistry INSTANCE = new ProtocolRegistry();

    // 存储协议枚举 → 处理器的映射（线程安全）
    private final Map<ProtocolEnum, ProtocolHandler> handlerMap = new ConcurrentHashMap<>();

    // 私有构造器（单例）
    private ProtocolRegistry() {}

    // ========== 注册方法 ==========
    /**
     * 注册协议处理器
     * @param protocolEnum 协议枚举（Key）
     * @param handler 处理器（Lambda/实现类）
     */
    public void register(ProtocolEnum protocolEnum, ProtocolHandler handler) {
        if (protocolEnum == null || handler == null) {
            throw new IllegalArgumentException("协议枚举和处理器不能为空");
        }
        handlerMap.put(protocolEnum, handler);
    }

    // ========== 处理方法 ==========
    /**
     * 执行协议处理（核心调用入口）
     * @param protocolEnum 协议枚举
     * @param requestParams 请求参数（字节数组）
     * @return 处理结果（有返回值则返回字节数组，无返回值返回null）
     */
    public byte[] handle(ProtocolEnum protocolEnum, byte[] requestParams) {
        ProtocolHandler handler = handlerMap.get(protocolEnum);
        if (handler == null) {
            throw new IllegalStateException("未找到协议处理器：" + protocolEnum.getProtocol());
        }
        // 执行处理器逻辑（有返回值则返回，无返回值返回null）
        return handler.handle(requestParams);
    }

    // ========== 静态工具方法 ==========
    /** 根据code执行处理（适配P2PMessage的type字段） */
    public byte[] handleByCode(int code, byte[] requestParams) {
        ProtocolEnum protocolEnum = ProtocolEnum.fromCode(code);
        return handle(protocolEnum, requestParams);
    }

    /** 根据协议字符串执行处理 */
    public byte[] handleByProtocol(String protocol, byte[] requestParams) {
        ProtocolEnum protocolEnum = ProtocolEnum.fromProtocol(protocol);
        return handle(protocolEnum, requestParams);
    }

    // 获取单例实例
    public static ProtocolRegistry getInstance() {
        return INSTANCE;
    }

}