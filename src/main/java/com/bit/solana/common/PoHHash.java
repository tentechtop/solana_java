package com.bit.solana.common;

/**
 * PoH（Proof of History）哈希（32字节），用于Solana的时序证明，记录事件的先后顺序
 */
public class PoHHash extends ByteHash32 {
    // PoH哈希的零值常量（如初始时序状态的哈希）
    public static final PoHHash ZERO = new PoHHash(new byte[HASH_LENGTH]);

    private PoHHash(byte[] value) {
        super(value); // 复用基类的长度校验和不可变性保证
    }

    /**
     * 从字节数组创建PoHHash实例
     * @param bytes 32字节的原始哈希数组
     * @return PoHHash实例
     */
    public static PoHHash fromBytes(byte[] bytes) {
        return new PoHHash(bytes);
    }

    /**
     * 从十六进制字符串创建PoHHash实例
     * @param hex 64位十六进制字符串（大小写均可）
     * @return PoHHash实例
     */
    @Override
    public PoHHash fromHex(String hex) {
        byte[] bytes = hexToBytes(hex); // 复用基类的十六进制转字节逻辑
        return new PoHHash(bytes);
    }
}