package com.bit.solana.p2p.protocol;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;


/**
 * 协议枚举（作为注册Key）
 */
@Getter
@Slf4j
public enum ProtocolEnum {
    // 枚举项：(code, 协议字符串)
    CHAIN_V1(1, "/chain/1.0.0"),
    BLOCK_V1(2, "/block/1.0.0"),
    PING_V1(3, "/ping/1.0.0");

    // Getter
    // 协议编码（对应P2PMessage的type字段）
    private final int code;
    // 协议字符串标识
    private final String protocol;

    ProtocolEnum(int code, String protocol) {
        this.code = code;
        this.protocol = protocol;
    }

    // ========== 辅助方法 ==========
    /** 根据code反向查找枚举 */
    public static ProtocolEnum fromCode(int code) {
        for (ProtocolEnum e : values()) {
            if (e.getCode() == code) {
                return e;
            }
        }
        throw new IllegalArgumentException("无效的协议code：" + code);
    }

    /** 根据协议字符串反向查找枚举（标准化处理） */
    public static ProtocolEnum fromProtocol(String protocol) {
        String standardized = protocol.trim().toLowerCase().replaceAll("/+", "/").replaceAll("/$", "");
        for (ProtocolEnum e : values()) {
            if (e.getProtocol().equals(standardized)) {
                return e;
            }
        }
        throw new IllegalArgumentException("未注册的协议字符串：" + protocol);
    }

}