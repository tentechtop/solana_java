package com.bit.solana.database;

import lombok.Getter;

import java.util.HashMap;
import java.util.Map;

/**
 * 表名枚举（每个表对应唯一int标识，用于缓存键生成）
 */
public enum TableEnum {
    CHAIN((short) 1),   // 链信息表（int标识1）
    BLOCK((short) 2),   // 区块信息表（int标识2）
    UTXO((short) 3),    // 其他表（可扩展）
    TRANSACTION((short) 4);

    @Getter
    private final short code;  // 表的int标识

    TableEnum(short code) {
        this.code = code;
    }

    // 缓存：标识 -> 枚举实例（避免频繁查找）
    private static final Map<Short, TableEnum> CODE_TO_ENUM = new HashMap<>();

    static {
        for (TableEnum table : values()) {
            CODE_TO_ENUM.put(table.code, table);
        }
    }

    // 根据int标识获取枚举实例（用于解析缓存键）
    public static TableEnum getByCode(int code) {
        return CODE_TO_ENUM.get(code);
    }

    // 根据表名（实际业务表名，如"chain"）获取枚举实例（用于适配原逻辑）
    public static TableEnum getByTableName(String tableName) {
        for (TableEnum table : values()) {
            if (table.name().equalsIgnoreCase(tableName)) {
                return table;
            }
        }
        return null;
    }
}