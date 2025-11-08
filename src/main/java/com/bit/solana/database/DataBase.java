package com.bit.solana.database;

//KV数据库操作 自带内存缓存 自定义缓存大小
public interface DataBase {
    //Table 是表约束数据类型
    // 数据库操作 增删改查 批量增删改查
    // 迭代器
    // 分页  自动提交

    boolean isExist(String table,byte[] key);

    void insert(String table,byte[] key, byte[] value);
    void delete(String table,byte[] key);
    void update(String table,byte[] key, byte[] value);
    byte[] get(String table,byte[] key);

    //在一个事务内完成 自动提交
    void batchInsert(String table,byte[][] keys, byte[][] values);
    void batchDelete(String table,byte[][] keys);
    void batchUpdate(String table,byte[][] keys, byte[][] values);
    byte[][] batchGet(String table,byte[][] keys);
    void close();
    void compact(byte[] start, byte[] limit);

}
