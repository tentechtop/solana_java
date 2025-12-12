package com.bit.solana.quic;


import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.netty.channel.socket.DatagramChannel;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timer;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static com.bit.solana.config.CommonConfig.NODE_EXPIRATION_TIME;
import static com.bit.solana.quic.QuicConstants.ReceiveMap;
import static com.bit.solana.quic.QuicConstants.SendMap;

/**
 * 连接管理器（全局连接池）
 */
@Slf4j
public class QuicConnectionManager {

    private static final Cache<Long, QuicConnection> CONNECTION_MAP  = Caffeine.newBuilder()
            .maximumSize(10000)
            .expireAfterAccess(NODE_EXPIRATION_TIME, TimeUnit.SECONDS) //按访问过期，长期不活跃直接淘汰
            .recordStats()
            .build();


    /**
     * 创建或者或者连接
     */
    public static QuicConnection createOrGetConnection(InetSocketAddress remoteAddress) {

        return null;
    }


    /**
     * 获取连接
     */
    public static QuicConnection getConnection(long connectionId) {
        return CONNECTION_MAP.getIfPresent(connectionId);
    }

    /**
     * 获取连接数
     */
    public static int getConnectionCount() {
        return CONNECTION_MAP.asMap().size();
    }

    /**
     * 添加连接
     */
    public static void addConnection(long connectionId, QuicConnection connection) {
        CONNECTION_MAP.put(connectionId, connection);
        log.info("[连接添加] 连接ID:{}", connectionId);
    }

    /**
     * 移除连接
     */
    public static void removeConnection(long connectionId) {
        QuicConnection removed = CONNECTION_MAP.asMap().remove(connectionId);
        if (removed != null) {
            log.info("[连接移除] 连接ID:{}", connectionId);
        }
    }

    /**
     * 检查连接是否存在
     */
    public static boolean hasConnection(long connectionId) {
        return CONNECTION_MAP.getIfPresent(connectionId) != null;
    }

    /**
     * 获取所有连接ID
     */
    public static java.util.Set<Long> getAllConnectionIds() {
        return CONNECTION_MAP.asMap().keySet();
    }

    /**
     * 清空所有连接
     */
    public static void clearAllConnections() {
        int count = CONNECTION_MAP.asMap().size();
        CONNECTION_MAP.invalidateAll();
        log.info("[清空连接] 清除了{}个连接", count);
    }



}
