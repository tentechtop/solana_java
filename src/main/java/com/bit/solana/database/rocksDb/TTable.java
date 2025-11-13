// RTable.java - 列族管理
package com.bit.solana.database.rocksDb;

import com.bit.solana.database.TableEnum;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.rocksdb.BlockBasedTableConfig;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;

import java.util.HashMap;
import java.util.Map;

@Slf4j
public class TTable {
    // 核心：表枚举 -> 列族 映射
    private static final Map<Short, ColumnFamily> tableToCfMap = new HashMap<>();

    // 列族枚举定义
    enum ColumnFamily {
        // 链信息表列族
        CHAIN(TableEnum.CHAIN.getCode(), "chain", new ColumnFamilyOptions()),

        // 区块信息表列族
        BLOCK(TableEnum.BLOCK.getCode(), "block", new ColumnFamilyOptions()
                .setTableFormatConfig(new BlockBasedTableConfig()
                        .setBlockCacheSize(128 * 1024 * 1024)
                        .setCacheIndexAndFilterBlocks(true)));

        final short tableCode;       // 关联的表标识
        final String actualName;     // 实际存储的列族名称
        final ColumnFamilyOptions options;  // 列族配置
        @Setter @Getter
        private ColumnFamilyHandle handle;  // 列族句柄

        ColumnFamily(short tableCode, String actualName, ColumnFamilyOptions options) {
            this.tableCode = tableCode;
            this.actualName = actualName;
            this.options = options;
        }
    }

    // 静态初始化：建立表标识与列族的映射
    static {
        for (ColumnFamily cf : ColumnFamily.values()) {
            tableToCfMap.put(cf.tableCode, cf);
        }
        log.info("RTable静态初始化完成，加载列族数量：{}", tableToCfMap.size());
    }

    /**
     * 根据表枚举获取列族句柄
     */
    public static ColumnFamilyHandle getColumnFamilyHandle(TableEnum tableEnum) {
        if (tableEnum == null) {
            log.warn("表枚举为空，无法获取列族句柄");
            return null;
        }

        ColumnFamily cf = tableToCfMap.get(tableEnum.getCode());
        if (cf != null) {
            return cf.getHandle();
        }
        log.warn("表不存在: {}", tableEnum);
        return null;
    }

    /**
     * 获取所有列族描述符
     */
    public static Map<Short, ColumnFamilyDescriptor> getColumnFamilyDescriptors() {
        Map<Short, ColumnFamilyDescriptor> descriptors = new HashMap<>();
        for (ColumnFamily cf : ColumnFamily.values()) {
            descriptors.put(cf.tableCode,
                    new ColumnFamilyDescriptor(cf.actualName.getBytes(), cf.options));
        }
        return descriptors;
    }

    /**
     * 获取所有列族枚举
     */
    public static ColumnFamily[] getAllColumnFamilies() {
        return ColumnFamily.values();
    }
}