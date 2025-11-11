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
public class table {
    // 核心：表名 -> 列族 映射（表名即ColumnFamily的actualName）
    private final Map<String, ColumnFamily> tableToCfMap = new HashMap<>();
    // 枚举ColumnFamily：补充getActualName方法（原有字段不变）

    enum ColumnFamily {
        //链信息
        CHAIN("CHAIN", "chain",new ColumnFamilyOptions()),

        //区块信息
        BLOCK("BLOCK", "utxo",new ColumnFamilyOptions()
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

    /**
     * 初始化
     */
}
