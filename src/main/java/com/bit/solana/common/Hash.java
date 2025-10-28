package com.bit.solana.common;

import lombok.Data;

import java.util.Arrays;

@Data
public class Hash {
    // 哈希固定长度为32字节
    public static final int HASH_LENGTH = 32;

    // 十六进制字符映射表
    private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();

    // 存储32字节哈希数据
    private final byte[] value;


    /**
     * 私有构造方法，确保只能通过byteToHash创建实例
     * @param value 32字节的哈希字节数组
     * @throws NullPointerException 若输入为null
     * @throws IllegalArgumentException 若输入长度不是32字节
     */
    private Hash(byte[] value) {
        if (value == null) {
            throw new NullPointerException("Hash value cannot be null");
        }
        if (value.length != HASH_LENGTH) {
            throw new IllegalArgumentException("Hash must be exactly " + HASH_LENGTH + " bytes long, but got " + value.length);
        }
        // 拷贝输入数组，防止外部修改内部数据
        this.value = Arrays.copyOf(value, HASH_LENGTH);
    }

    /**
     * 将字节数组转换为Hash实例（静态工厂方法）
     * @param bytes 输入的字节数组
     * @return 32字节的Hash实例
     */
    public static Hash byteToHash(byte[] bytes) {
        return new Hash(bytes);
    }

    /**
     * 将哈希数据转换为十六进制字符串
     * @return 64位十六进制字符串（小写）
     */
    public String toHex() {
        StringBuilder hexBuilder = new StringBuilder(HASH_LENGTH * 2);
        for (byte b : value) {
            // 高4位转换为十六进制
            hexBuilder.append(HEX_CHARS[(b >>> 4) & 0x0F]);
            // 低4位转换为十六进制
            hexBuilder.append(HEX_CHARS[b & 0x0F]);
        }
        return hexBuilder.toString();
    }

    /**
     * 重写equals方法，基于哈希字节内容比较
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Hash hash = (Hash) o;
        return Arrays.equals(value, hash.value);
    }


    /**
     * 重写hashCode方法，基于哈希字节内容生成
     */
    @Override
    public int hashCode() {
        return Arrays.hashCode(value);
    }

}
