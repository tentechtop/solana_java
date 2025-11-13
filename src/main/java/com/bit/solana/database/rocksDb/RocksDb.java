package com.bit.solana.database.rocksDb;

import com.bit.solana.database.DataBase;
import com.bit.solana.database.DbConfig;
import lombok.extern.slf4j.Slf4j;
import org.rocksdb.*;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Slf4j
@Component
public class RocksDb implements DataBase {

    private RocksDB db;
    private final ReadWriteLock rwLock = new ReentrantReadWriteLock();
    private String dbPath;


    @Override
    public boolean createDatabase(DbConfig config) {
        String path = config.getPath();//存储路径
        if (path.isEmpty()) {
            return false;
        }
        try {
            File dbDir = new File(dbPath);
            if (!dbDir.exists() && !dbDir.mkdirs()) {
                log.error("创建数据库目录失败: {}", dbPath);
                return false;
            }

            // 初始化列族
            List<ColumnFamilyDescriptor> cfDescriptors = new ArrayList<>();
            List<ColumnFamilyHandle> cfHandles = new ArrayList<>();

            // 添加默认列族


            // 添加自定义表（列族）
            for (RTable.ColumnFamily cf : RTable.ColumnFamily.values()) {

            }

            // 打开数据库
            DBOptions options = new DBOptions()
                    .setCreateIfMissing(true)
                    .setCreateMissingColumnFamilies(true)
                    .setInfoLogLevel(InfoLogLevel.ERROR_LEVEL);

            db = RocksDB.open(options, dbPath, cfDescriptors, cfHandles);

            // 绑定列族句柄
            for (int i = 0; i < RTable.ColumnFamily.values().length; i++) {
                RTable.ColumnFamily.values()[i].setHandle(cfHandles.get(i + 1));
            }

            // 注册关闭钩子
            Runtime.getRuntime().addShutdownHook(new Thread(this::close));
            log.info("RocksDB创建成功，路径: {}", dbPath);
            return true;
        } catch (RocksDBException e) {
            log.error("创建RocksDB失败", e);
            return false;
        }
    }


    @Override
    public boolean closeDatabase() {
        try {
            close();
            log.info("数据库已关闭");
            return true;
        } catch (Exception e) {
            log.error("关闭数据库失败", e);
            return false;
        }
    }

    @Override
    public boolean isExist(String table, byte[] key) {
        rwLock.readLock().lock();
        try {
            ColumnFamilyHandle cfHandle = getColumnFamilyHandle(table);
            if (cfHandle == null) return false;
            return db.get(cfHandle, key) != null;
        } catch (RocksDBException e) {
            log.error("检查键是否存在失败, table={}", table, e);
            return false;
        } finally {
            rwLock.readLock().unlock();
        }
    }


    @Override
    public void insert(String table, byte[] key, byte[] value) {

    }

    @Override
    public void delete(String table, byte[] key) {

    }

    @Override
    public void update(String table, byte[] key, byte[] value) {

    }

    @Override
    public byte[] get(String table, byte[] key) {
        return new byte[0];
    }

    @Override
    public int count(String table) {
        return 0;
    }

    @Override
    public void batchInsert(String table, byte[][] keys, byte[][] values) {

    }

    @Override
    public void batchDelete(String table, byte[][] keys) {

    }

    @Override
    public void batchUpdate(String table, byte[][] keys, byte[][] values) {

    }

    @Override
    public byte[][] batchGet(String table, byte[][] keys) {
        return new byte[0][];
    }

    @Override
    public void close() {

    }

    @Override
    public void compact(byte[] start, byte[] limit) {

    }


    /**
     * 获取表对应的列族句柄
     */
    private ColumnFamilyHandle getColumnFamilyHandle(String table) {
        return RTable.getColumnFamilyHandleByTable(table);
    }
}
