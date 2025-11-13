package com.bit.solana.database.rocksDb;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.rocksdb.BlockBasedTableConfig;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;

import java.util.HashMap;
import java.util.Map;

@Slf4j
public class RTable {
    // 核心：表名 -> 列族 映射（表名即ColumnFamily的actualName）
    private static  Map<String, ColumnFamily> tableToCfMap = new HashMap<>();

    // 枚举ColumnFamily：补充getActualName方法（原有字段不变）

    enum ColumnFamily {
        //链信息
        CHAIN("CHAIN", "chain",new ColumnFamilyOptions()),

        //区块信息
        BLOCK("BLOCK", "block",new ColumnFamilyOptions()
                .setTableFormatConfig(new BlockBasedTableConfig()
                        .setBlockCacheSize(128 * 1024 * 1024)
                        .setCacheIndexAndFilterBlocks(true))),

        ;
        final String logicalName;
        final String actualName;
        final ColumnFamilyOptions options;
        ColumnFamily(String logicalName, String actualName, ColumnFamilyOptions options) {
            this.logicalName = logicalName;
            this.actualName = actualName;
            this.options = options;
        }
        @Setter
        @Getter
        private ColumnFamilyHandle handle;
    }

    // 关键：静态代码块，类加载时自动执行，初始化映射
    static {
        for (ColumnFamily cf : ColumnFamily.values()) {
            tableToCfMap.put(cf.actualName, cf);
        }
        log.info("RTable静态初始化完成，加载列族数量：{}", tableToCfMap.size());
    }


    /**
     * 初始化：将所有ColumnFamily枚举值添加到tableToCfMap中
     */
    public RTable() {
        // 遍历ColumnFamily所有枚举值，以actualName为key，枚举实例为value存入map
        for (ColumnFamily cf : ColumnFamily.values()) {
            tableToCfMap.put(cf.actualName, cf);
        }
        log.info("RTable初始化完成，加载列族数量：{}", tableToCfMap.size());
    }

    /**
     * 获取表对应的列族句柄
     */
    public static ColumnFamilyHandle getColumnFamilyHandleByTable(String table) {
        // 从map中根据表名（actualName）获取对应的ColumnFamily
        ColumnFamily cf = tableToCfMap.get(table);
        if (cf != null) {
            return cf.getHandle(); // 返回列族句柄
        }
        log.warn("表不存在: {}", table);
        return null;
    }

}
