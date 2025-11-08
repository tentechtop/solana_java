package com.bit.solana.poh.impl;

import com.bit.solana.poh.POHCache;
import com.bit.solana.poh.POHException;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class MemoryPOHCache implements POHCache {
    // 本地缓存
    private final ConcurrentHashMap<String, byte[]> bytesCache = new ConcurrentHashMap<>(1024);
    private final ConcurrentHashMap<String, Long> longCache = new ConcurrentHashMap<>(1024);

    @Override
    public byte[] getBytes(String key) throws POHException {
        return bytesCache.get(key);
    }

    @Override
    public long getLong(String key) throws POHException {
        return longCache.getOrDefault(key, 0L);
    }

    @Override
    public void putBytes(String key, byte[] value) throws POHException {
        bytesCache.put(key, value);
    }

    @Override
    public void putLong(String key, long value) throws POHException {
        longCache.put(key, value);
    }

    @Override
    public void batchPut(Map<String, byte[]> bytesEntries, Map<String, Long> longEntries) throws POHException {
        bytesCache.putAll(bytesEntries);
        longCache.putAll(longEntries);
    }

    @Override
    public void initDefaultState() throws POHException {
        // 初始化默认哈希（SHA-256空值哈希）
        byte[] initialHash = new byte[32];
        bytesCache.putIfAbsent(KEY_LAST_HASH, initialHash);
        longCache.putIfAbsent(KEY_EMPTY_COUNTER, 0L);
        longCache.putIfAbsent(KEY_CHAIN_HEIGHT, 0L);
    }
}
