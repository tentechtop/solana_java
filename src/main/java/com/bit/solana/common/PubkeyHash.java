package com.bit.solana.common;

public class PubkeyHash extends ByteHash32 {
    // PoH哈希的零值常量（如初始时序状态的哈希）
    public static final PubkeyHash ZERO = new PubkeyHash(new byte[HASH_LENGTH]);

    private PubkeyHash(byte[] value) {
        super(value); // 复用基类的长度校验和不可变性保证
    }

    /**
     * 从字节数组创建PoHHash实例
     * @param bytes 32字节的原始哈希数组
     * @return PoHHash实例
     */
    public static PubkeyHash fromBytes(byte[] bytes) {
        return new PubkeyHash(bytes);
    }

    /**
     * 从十六进制字符串创建PoHHash实例
     * @param hex 64位十六进制字符串（大小写均可）
     * @return PoHHash实例
     */
    @Override
    public PubkeyHash fromHex(String hex) {
        byte[] bytes = hexToBytes(hex); // 复用基类的十六进制转字节逻辑
        return new PubkeyHash(bytes);
    }
}