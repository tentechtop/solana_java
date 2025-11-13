package com.bit.solana.database;

import lombok.Getter;

import java.util.HashMap;
import java.util.Map;

/**
 * 表名枚举（每个表对应唯一int标识，用于缓存键生成）
 */
public enum TableEnum {
    CHAIN((short) 1),   // 链信息表
    BLOCK((short) 2);   // 区块信息表

    @Getter
    private final short code;  // 表的唯一标识

    TableEnum(short code) {
        this.code = code;
    }

    // 缓存：标识 -> 枚举实例
    private static final Map<Short, TableEnum> CODE_TO_ENUM = new HashMap<>();
    // 缓存：表名 -> 枚举实例
    private static final Map<String, TableEnum> NAME_TO_ENUM = new HashMap<>();

    static {
        for (TableEnum table : values()) {
            CODE_TO_ENUM.put(table.code, table);
            NAME_TO_ENUM.put(table.name().toLowerCase(), table);
        }
    }

    // 根据short标识获取枚举实例
    public static TableEnum getByCode(short code) {
        return CODE_TO_ENUM.get(code);
    }

    // 根据表名获取枚举实例
    public static TableEnum getByTableName(String tableName) {
        return tableName != null ? NAME_TO_ENUM.get(tableName.toLowerCase()) : null;
    }
}