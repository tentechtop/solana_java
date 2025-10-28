package com.bit.solana.common;

/**
 * 状态根哈希（32字节），标识区块链某一时刻的全局状态快照
 */
public class StateRootHash extends ByteHash32 {
    // 状态根的零哈希常量（如初始状态）
    public static final StateRootHash ZERO = new StateRootHash(new byte[HASH_LENGTH]);

    private StateRootHash(byte[] value) {
        super(value);
    }

    /**
     * 从字节数组创建StateRootHash
     */
    public static StateRootHash fromBytes(byte[] bytes) {
        return new StateRootHash(bytes);
    }

    /**
     * 从十六进制字符串创建StateRootHash
     */
    @Override
    public StateRootHash fromHex(String hex) {
        byte[] bytes = hexToBytes(hex);
        return new StateRootHash(bytes);
    }
}
