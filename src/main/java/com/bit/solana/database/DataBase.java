package com.bit.solana.database;

//KV数据库操作 自带内存缓存 自定义缓存大小
public interface DataBase {
    //Table 是表约束数据类型
    // 数据库操作 增删改查 批量增删改查
    // 迭代器
    // 分页  自动提交

    /**
     * 创建数据库
     * @param config
     * @return
     */
    boolean createDatabase(DbConfig config);

    /**
     * 关闭数据库
     * @return
     */
    boolean closeDatabase();

    /**
     * 判断是否存在
     * @param table
     * @param key
     * @return
     */
    boolean isExist(String table,byte[] key);

    /**
     * 插入一条数据
     * @param table
     * @param key
     * @param value
     */
    void insert(String table,byte[] key, byte[] value);

    /**
     * 输出一条数据
     * @param table
     * @param key
     */
    void delete(String table,byte[] key);

    /**
     * 修改一条数据
     * @param table
     * @param key
     * @param value
     */
    void update(String table,byte[] key, byte[] value);

    /**
     * 获取一条数据
     * @param table
     * @param key
     * @return
     */
    byte[] get(String table,byte[] key);

    /**
     * 数据数量
     * @param table
     * @return
     */
    int count(String table);

    //在一个事务内完成 自动提交
    void batchInsert(String table,byte[][] keys, byte[][] values);
    void batchDelete(String table,byte[][] keys);
    void batchUpdate(String table,byte[][] keys, byte[][] values);
    byte[][] batchGet(String table,byte[][] keys);
    void close();
    void compact(byte[] start, byte[] limit);

    //提交一堆操作 在一个事务内完成  保障原子性



}
