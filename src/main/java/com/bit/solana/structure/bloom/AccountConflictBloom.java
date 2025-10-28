package com.bit.solana.structure.bloom;

import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.BitSet;

/**
 * 账户冲突布隆过滤器（Account Conflict Bloom Filter）
 * 存储交易涉及的"可写账户公钥哈希"，用于快速判断两笔交易是否存在账户冲突，优化并行验证
 * 遵循Solana协议规定：
 * 1. 位数组长度：{@value #PROTOCOL_BIT_LENGTH} bit（{@value #PROTOCOL_BIT_LENGTH / 8} byte）
 * 2. 哈希函数数量：{@value #PROTOCOL_HASH_COUNT} 个（SHA-256带盐值）
 * 3. 字节序：统一使用大端序（Big-Endian），确保全网节点计算结果一致
 */
@EqualsAndHashCode
@ToString(of = {"bitLength", "hashCount"})
public class AccountConflictBloom {
    // ========================== 协议固定参数（Solana规定，确保全网一致）==========================
    /**
     * 布隆过滤器位数组长度（bit），Solana协议约定（例如：32768 bit = 4096 byte）
     * 实际值需参考Solana源码或协议文档，此处为示例
     */
    public static final int PROTOCOL_BIT_LENGTH = 32768;

    /**
     * 哈希函数数量，Solana协议约定（例如：3个哈希函数）
     * 数量越多，误判率越低，但性能略降
     */
    public static final int PROTOCOL_HASH_COUNT = 3;

    /**
     * 哈希算法（用于生成布隆过滤器的映射位置，Solana通常使用SHA-256的截断结果）
     */
    private static final String HASH_ALGORITHM = "SHA-256";

    // ========================== 内部状态 ==========================
    /**
     * 位数组（存储布隆过滤器的核心数据，每个bit表示一个映射位置）
     * 使用BitSet封装，简化bit操作（避免直接处理byte[]的bit偏移）
     */
    private final BitSet bitSet;

    // ========================== 构造与初始化 ==========================
    /**
     * 私有构造，通过静态工厂方法创建实例，确保参数符合协议
     */
    private AccountConflictBloom(BitSet bitSet) {
        // 校验位数组长度是否符合协议（避免无效长度导致的判断错误）
        if (bitSet.length() > PROTOCOL_BIT_LENGTH) {
            throw new IllegalArgumentException("Bloom filter bit length exceeds protocol limit (" + PROTOCOL_BIT_LENGTH + ")");
        }
        this.bitSet = new BitSet(PROTOCOL_BIT_LENGTH);
        this.bitSet.or(bitSet); // 拷贝输入的bitSet，确保内部状态不可变（或按需设计为可变）
    }

    /**
     * 创建空的布隆过滤器（初始所有bit为0）
     */
    public static AccountConflictBloom createEmpty() {
        return new AccountConflictBloom(new BitSet(PROTOCOL_BIT_LENGTH));
    }

    /**
     * 从字节数组反序列化布隆过滤器（用于网络传输或存储后恢复）
     * @param bytes 序列化后的字节数组（长度必须为 PROTOCOL_BIT_LENGTH / 8）
     */
    public static AccountConflictBloom fromBytes(byte[] bytes) {
        // 校验字节数组长度是否符合协议（位数组长度 / 8 = 字节数）
        int expectedByteLength = PROTOCOL_BIT_LENGTH / 8;
        if (bytes == null || bytes.length != expectedByteLength) {
            throw new IllegalArgumentException("Invalid bloom filter bytes length (expected " + expectedByteLength + ", got " + (bytes == null ? 0 : bytes.length) + ")");
        }
        BitSet bitSet = BitSet.valueOf(bytes);
        return new AccountConflictBloom(bitSet);
    }

    // ========================== 核心操作 ==========================
    /**
     * 向布隆过滤器添加一个账户公钥哈希（32字节）
     * 原理：通过PROTOCOL_HASH_COUNT个哈希函数计算映射位置，将对应bit设为1
     * @param accountHash 账户公钥的哈希（32字节，如AccountHash的value）
     * @return 新的布隆过滤器实例（若设计为不可变）或当前实例（若设计为可变）
     */
    public AccountConflictBloom add(byte[] accountHash) {
        // 校验输入（账户哈希必须为32字节，符合Solana账户公钥哈希规范）
        if (accountHash == null || accountHash.length != 32) {
            throw new IllegalArgumentException("Account hash must be 32 bytes");
        }

        BitSet newBitSet = (BitSet) this.bitSet.clone(); // 若不可变，克隆后修改
        for (int i = 0; i < PROTOCOL_HASH_COUNT; i++) {
            // 生成第i个哈希函数的结果（通过盐值区分不同哈希函数）
            byte[] hash = computeHashWithSalt(accountHash, i);
            // 将哈希结果转换为位数组中的位置（取模确保在PROTOCOL_BIT_LENGTH范围内）
            int position = Math.abs(bytesToInt(hash)) % PROTOCOL_BIT_LENGTH;
            newBitSet.set(position);
        }
        return new AccountConflictBloom(newBitSet);
    }

    /**
     * 判断一个账户公钥哈希是否可能存在于布隆过滤器中（可能有误判）
     * 原理：检查所有哈希函数映射的bit是否均为1，若有一个为0则一定不存在
     * @param accountHash 账户公钥的哈希（32字节）
     * @return true=可能存在（有冲突风险），false=一定不存在（无冲突）
     */
    public boolean mightContain(byte[] accountHash) {
        if (accountHash == null || accountHash.length != 32) {
            throw new IllegalArgumentException("Account hash must be 32 bytes");
        }

        for (int i = 0; i < PROTOCOL_HASH_COUNT; i++) {
            byte[] hash = computeHashWithSalt(accountHash, i);
            int position = Math.abs(bytesToInt(hash)) % PROTOCOL_BIT_LENGTH;
            if (!bitSet.get(position)) {
                return false; // 有一个bit为0，一定不存在
            }
        }
        return true; // 所有bit为1，可能存在（有误判可能）
    }

    // ========================== 序列化 ==========================
    /**
     * 序列化布隆过滤器为字节数组（用于网络传输或存储）
     * @return 字节数组（长度为 PROTOCOL_BIT_LENGTH / 8）
     */
    public byte[] toBytes() {
        // 确保输出的字节数组长度符合协议（BitSet可能自动截断，需补全）
        byte[] bytes = bitSet.toByteArray();
        if (bytes.length < PROTOCOL_BIT_LENGTH / 8) {
            byte[] padded = new byte[PROTOCOL_BIT_LENGTH / 8];
            System.arraycopy(bytes, 0, padded, 0, bytes.length);
            return padded;
        }
        return bytes;
    }

    // ========================== 辅助方法 ==========================
    /**
     * 带盐值的哈希计算（区分不同的哈希函数）
     * @param accountHash 账户哈希
     * @param salt 盐值（0,1,...,PROTOCOL_HASH_COUNT-1）
     * @return 哈希结果（前4字节用于计算位置）
     */
    private byte[] computeHashWithSalt(byte[] accountHash, int salt) {
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            // 盐值转为字节（与账户哈希拼接后计算哈希）
            byte[] saltBytes = String.valueOf(salt).getBytes(StandardCharsets.UTF_8);
            digest.update(saltBytes);
            digest.update(accountHash);
            return digest.digest(); // 返回SHA-256完整哈希（256bit=32字节）
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not found", e); // 理论上不会发生
        }
    }

    /**
     * 将哈希字节数组转换为int（严格遵循大端序，与Solana协议对齐）
     * 规则：哈希前4字节按"高位字节在前"解析，确保全网节点计算的bit位置一致
     * @param hash SHA-256哈希结果（32字节），取前4字节用于计算bit位置
     * @return 大端序解析后的int值（用于映射布隆过滤器的bit位置）
     */
    private int bytesToInt(byte[] hash) {
        // 大端序解析：hash[0] = 最高位字节，hash[3] = 最低位字节
        // 示例：若hash前4字节为 0x12 0x34 0x56 0x78 → 解析为 0x12345678（int值）
        return ((hash[0] & 0xFF) << 24) |  // 第0字节 → 高8位（24-31位）
                ((hash[1] & 0xFF) << 16) |  // 第1字节 → 次高8位（16-23位）
                ((hash[2] & 0xFF) << 8) |   // 第2字节 → 次低8位（8-15位）
                (hash[3] & 0xFF);           // 第3字节 → 低8位（0-7位）
    }
}