package com.bit.solana.poh.impl;

import com.bit.solana.poh.POHCache;
import com.bit.solana.poh.POHException;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class POHCacheImpl implements POHCache {
    // 本地缓存
    private Cache<String, byte[]> bytesCache;
    private Cache<String, Long> longCache;

    @PostConstruct
    public void init() {
        // 配置缓存：设置过期时间防止内存溢出
        this.bytesCache = CacheBuilder.newBuilder()
                .expireAfterWrite(1, TimeUnit.HOURS)
                .maximumSize(1000)
                .build();

        this.longCache = CacheBuilder.newBuilder()
                .expireAfterWrite(1, TimeUnit.HOURS)
                .maximumSize(1000)
                .build();
    }

    @Override
    public byte[] getBytes(String key) throws POHException {
        return bytesCache.getIfPresent(key);
    }

    @Override
    public long getLong(String key) throws POHException {
        return 0;
    }

    @Override
    public void putBytes(String key, byte[] value) throws POHException {

    }

    @Override
    public void putLong(String key, long value) throws POHException {

    }

    @Override
    public void batchPut(Map<String, byte[]> bytesEntries, Map<String, Long> longEntries) throws POHException {

    }

    @Override
    public void initDefaultState() throws POHException {

    }
}
