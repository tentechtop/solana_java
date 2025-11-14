package com.bit.solana.mock;

import java.util.Objects;

/**
 * 交易实体类，包含手续费字段
 */
public class Transaction {
    private final String id; // 交易唯一标识（必须存在）
    private long fee;

    public Transaction(String id, long fee) {
        this.id = id;
        this.fee = fee;
    }

    // 关键：基于唯一ID判断相等
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Transaction that = (Transaction) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    // getter
    public long getFee() { return fee; }
    public String getId() { return id; }
}