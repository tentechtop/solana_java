package com.bit.solana.common;

import lombok.*;

import java.io.Serializable;
import java.util.Arrays;

/**
 * Solana中32字节哈希的通用基类，封装共同逻辑（长度校验、不可变性、转换方法等）
 * 具体哈希类型（如区块哈希、状态根）应继承此类
 */
@EqualsAndHashCode(of = "value")
@ToString(of = "hexValue")
@Data
public abstract class ByteHash32 implements Serializable {
    public static final int HASH_LENGTH = 32;
    private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();

    // 存储32字节哈希数据（私有且不可变）
    private byte[] value;
    // 缓存十六进制字符串（避免重复计算）
    @Getter(AccessLevel.PROTECTED)
    private final String hexValue;

    /**
     * 构造方法，由子类调用，强制校验长度
     * @param value 32字节哈希的原始字节数组
     * @throws IllegalArgumentException 若长度不符
     */
    protected ByteHash32(byte[] value) {
        if (value == null) {
            throw new NullPointerException("Hash value cannot be null");
        }
        if (value.length != HASH_LENGTH) {
            throw new IllegalArgumentException("Hash must be " + HASH_LENGTH + " bytes, got " + value.length);
        }
        this.value = Arrays.copyOf(value, HASH_LENGTH); // 防御性拷贝
        this.hexValue = toHexInternal(this.value); // 初始化时计算并缓存十六进制
    }

    /**
     * 从十六进制字符串解析为哈希实例（由子类实现，确保类型正确）
     * @param hex 64位十六进制字符串
     * @return 具体哈希类型的实例
     */
    public abstract ByteHash32 fromHex(String hex);

    /**
     * 获取原始字节数组（返回拷贝，确保不可变性）
     */
    public byte[] getValue() {
        return Arrays.copyOf(value, HASH_LENGTH);
    }

    /**
     * 转换为十六进制字符串（使用缓存值，提升性能）
     */
    public String toHex() {
        return hexValue;
    }

    /**
     * 判断是否为零哈希（全0字节）
     */
    public boolean isZero() {
        for (byte b : value) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * 内部方法：字节数组转十六进制字符串
     */
    private String toHexInternal(byte[] bytes) {
        StringBuilder sb = new StringBuilder(HASH_LENGTH * 2);
        for (byte b : bytes) {
            sb.append(HEX_CHARS[(b >>> 4) & 0x0F]);
            sb.append(HEX_CHARS[b & 0x0F]);
        }
        return sb.toString();
    }

    /**
     * 内部方法：十六进制字符串转字节数组（供子类fromHex调用）
     */
    protected byte[] hexToBytes(String hex) {
        if (hex == null || hex.length() != HASH_LENGTH * 2) {
            throw new IllegalArgumentException("Invalid hex string length for 32-byte hash");
        }
        byte[] bytes = new byte[HASH_LENGTH];
        for (int i = 0; i < HASH_LENGTH; i++) {
            int high = Character.digit(hex.charAt(i * 2), 16);
            int low = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (high == -1 || low == -1) {
                throw new IllegalArgumentException("Invalid hex character in: " + hex);
            }
            bytes[i] = (byte) ((high << 4) | low);
        }
        return bytes;
    }


    /**
     * 获取原始字节数组（返回拷贝，确保不可变性）
     * 与getValue()方法功能一致，提供统一的字节数组获取接口
     */
    public byte[] getBytes() {
        return Arrays.copyOf(value, HASH_LENGTH);
    }

    public void setValue(byte[] hash) {
        if (hash == null || hash.length != HASH_LENGTH) {
            throw new IllegalArgumentException("Hash must be " + HASH_LENGTH + " bytes, got " + hash.length);
        }
        this.value = hash;
    }
}
