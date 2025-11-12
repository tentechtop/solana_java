package com.bit.solana.structure.poh;

/**
 * POH事件类型枚举（映射1字节存储，兼容所有事件场景）
 */
public enum PohEventType {
    // 枚举值与字节值一一对应（范围：0~127，预留扩展空间）
    EMPTY((byte) 0x00, "空事件（仅维持哈希链）"),
    TRANSACTION((byte) 0x01, "交易事件（关联交易ID）"),
    BLOCK((byte) 0x02, "区块事件（关联区块Hash）"),
    SYSTEM((byte) 0x03, "系统事件（如Slot切换、共识消息）"),
    RESERVED_1((byte) 0x04, "预留类型1（未来扩展）"),
    RESERVED_2((byte) 0x05, "预留类型2（未来扩展）");

    // 事件类型对应的字节值（核心：确保1字节存储）
    private final byte code;
    // 事件类型描述（日志/调试用）
    private final String description;

    PohEventType(byte code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * 通过字节值获取对应的枚举实例（反序列化用）
     */
    public static PohEventType fromCode(byte code) {
        for (PohEventType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知的POH事件类型字节值：" + code);
    }

    // Getter方法
    public byte getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }
}