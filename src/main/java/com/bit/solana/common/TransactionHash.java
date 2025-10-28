package com.bit.solana.common;

/**
 * 交易哈希（TransactionHash，32字节），标识一笔交易的唯一ID，由交易数据SHA-256哈希得到
 */
public class TransactionHash extends ByteHash32 {
    // 交易哈希的零值常量（通常用于无效交易或占位场景）
    public static final TransactionHash ZERO = new TransactionHash(new byte[HASH_LENGTH]);

    private TransactionHash(byte[] value) {
        super(value); // 复用基类的32字节校验和不可变性保证
    }

    /**
     * 从字节数组创建TransactionHash实例
     * @param bytes 32字节的原始哈希数组
     * @return TransactionHash实例
     */
    public static TransactionHash fromBytes(byte[] bytes) {
        return new TransactionHash(bytes);
    }

    /**
     * 从十六进制字符串创建TransactionHash实例
     * @param hex 64位十六进制字符串（大小写均可）
     * @return TransactionHash实例
     */
    @Override
    public TransactionHash fromHex(String hex) {
        byte[] bytes = hexToBytes(hex); // 复用基类的十六进制转字节逻辑
        return new TransactionHash(bytes);
    }
}