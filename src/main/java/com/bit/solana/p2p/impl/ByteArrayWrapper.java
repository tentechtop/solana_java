package com.bit.solana.p2p.impl;

import lombok.Data;

import java.util.Arrays;

@Data
public class ByteArrayWrapper {
    private final byte[] data;
    private int hashCode; // 缓存哈希值提升性能

    public ByteArrayWrapper(byte[] data) {
        this.data = data;
        this.hashCode = Arrays.hashCode(data); // 基于内容计算哈希
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ByteArrayWrapper that = (ByteArrayWrapper) o;
        return Arrays.equals(data, that.data); // 基于内容比较
    }

    @Override
    public int hashCode() {
        return hashCode;
    }
}