package com.bit.solana.common;

/**
 * 最近投票哈希（recentVotesHash，32字节），用于跟踪验证者最近的投票集合，是共识状态的一部分
 */
public class RecentVotesHash extends ByteHash32 {
    // 最近投票哈希的零值常量（如初始状态下的空投票集合）
    public static final RecentVotesHash ZERO = new RecentVotesHash(new byte[HASH_LENGTH]);

    private RecentVotesHash(byte[] value) {
        super(value); // 复用基类的32字节校验和不可变性保证
    }

    /**
     * 从字节数组创建RecentVotesHash实例
     */
    public static RecentVotesHash fromBytes(byte[] bytes) {
        return new RecentVotesHash(bytes);
    }

    /**
     * 从十六进制字符串创建RecentVotesHash实例
     */
    @Override
    public RecentVotesHash fromHex(String hex) {
        byte[] bytes = hexToBytes(hex); // 复用基类的十六进制转字节逻辑
        return new RecentVotesHash(bytes);
    }
}