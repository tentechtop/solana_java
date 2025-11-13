package com.bit.solana.p2p.enums;

/**
 * 节点服务类型
 */
public enum NodeServerType {
    UNKNOWN(0),
    FULL(1),      // 全节点 保存所有数据
    LIGHT(2),     // 轻节点 保存并同步区块头
    OUTBOUND(3);  // 仅出站 不保存任何数据

    private final int value;
    private static final java.util.Map<Integer, NodeServerType> map = new java.util.HashMap<>();

    static {
        for (NodeServerType nodeType : NodeServerType.values()) {
            map.put(nodeType.value, nodeType);
        }
    }

    NodeServerType(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }

    public static NodeServerType valueOf(int value) {
        return map.get(value);
    }
}