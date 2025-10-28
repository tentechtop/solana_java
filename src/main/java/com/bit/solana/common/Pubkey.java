package com.bit.solana.common;

import lombok.EqualsAndHashCode;
import lombok.ToString;
import java.util.Arrays;

/**
 * 公钥封装（32字节），统一账户/程序的地址表示
 */
@EqualsAndHashCode
@ToString
public class Pubkey {
    public static final int LENGTH = 32;
    private final byte[] value;

    private Pubkey(byte[] value) {
        if (value.length != LENGTH) {
            throw new IllegalArgumentException("公钥必须为32字节");
        }
        this.value = value;
    }

    public static Pubkey fromBytes(byte[] bytes) {
        return new Pubkey(Arrays.copyOf(bytes, LENGTH));
    }

    public byte[] toBytes() {
        return Arrays.copyOf(value, LENGTH);
    }
}