package com.bit.solana.poh;

/**
 * POH 缓存接口：定义缓存的核心操作，解耦缓存实现与 POH 引擎
 */
public interface POHCache {
    // 缓存键常量（所有实现类共享）
    String KEY_LAST_HASH = "poh:lastHash";       // 最新哈希值（byte[] 类型）
    String KEY_EMPTY_COUNTER = "poh:emptyCounter";// 空事件计数器（String 类型，存储 long 值）
    String KEY_CHAIN_HEIGHT = "poh:chainHeight";  // 哈希链高度（String 类型，存储 long 值）

    /**
     * 读取缓存中的 byte[] 类型数据（如最新哈希值）
     * @param key 缓存键
     * @return 缓存值（null 表示无此键）
     * @throws POHException 缓存读取异常
     */
    byte[] getBytes(String key) throws POHException;

    /**
     * 读取缓存中的 long 类型数据（如空事件计数器、哈希链高度）
     * @param key 缓存键
     * @return 缓存值（默认返回 0 表示无此键）
     * @throws POHException 缓存读取异常或数据格式转换异常
     */
    long getLong(String key) throws POHException;

    /**
     * 写入 byte[] 类型数据到缓存（如最新哈希值）
     * @param key 缓存键
     * @param value 缓存值（不可为 null）
     * @throws POHException 缓存写入异常
     */
    void putBytes(String key, byte[] value) throws POHException;

    /**
     * 写入 long 类型数据到缓存（如空事件计数器、哈希链高度）
     * @param key 缓存键
     * @param value 缓存值
     * @throws POHException 缓存写入异常
     */
    void putLong(String key, long value) throws POHException;

    /**
     * 批量写入缓存（减少 IO 次数，提升性能）
     * @param bytesEntries byte[] 类型键值对（键不可重复）
     * @param longEntries long 类型键值对（键不可重复）
     * @throws POHException 批量写入异常（部分写入失败时会回滚或抛出异常）
     */
    void batchPut(
            java.util.Map<String, byte[]> bytesEntries,
            java.util.Map<String, Long> longEntries
    ) throws POHException;

    /**
     * 初始化缓存默认状态（首次启动或缓存数据丢失时调用）
     * @throws POHException 初始化异常
     */
    void initDefaultState() throws POHException;
}