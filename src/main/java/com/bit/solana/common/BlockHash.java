package com.bit.solana.common;

/**
 * 区块哈希（32字节），标识一个区块的唯一ID
 */
public class BlockHash extends ByteHash32 {

    // 零哈希常量（创世区块的父哈希等场景使用）
    public static final BlockHash ZERO = new BlockHash(new byte[HASH_LENGTH]);


    public BlockHash(byte[] value) {
        super(value);
    }

    /**
     * 从字节数组创建BlockHash
     */
    public static BlockHash fromBytes(byte[] bytes) {
        return new BlockHash(bytes);
    }

    /**
     * 从十六进制字符串创建BlockHash
     */
    @Override
    public BlockHash fromHex(String hex) {
        byte[] bytes = hexToBytes(hex);
        return new BlockHash(bytes);
    }


}
