package com.bit.solana.quic;


import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.netty.channel.socket.DatagramChannel;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

import static com.bit.solana.config.CommonConfig.NODE_EXPIRATION_TIME;

/**
 * 连接管理器（全局连接池）
 */
@Slf4j
public class QuicConnectionManager {

    //支持一万个连接
    private static final Cache<Long, QuicConnection> CONNECTION_MAP  = Caffeine.newBuilder()
            .maximumSize(10000)
            .expireAfterAccess(NODE_EXPIRATION_TIME, TimeUnit.SECONDS) //按访问过期，长期不活跃直接淘汰
            .recordStats()
            .build();


    public static QuicConnection getOrCreateConnection(DatagramChannel channel, InetSocketAddress local,
                                                       InetSocketAddress remote) {
        long connectionId = ConnectionIdGenerator.generate(local, remote);

        return CONNECTION_MAP.get(connectionId, id ->
                new QuicConnection(id, channel, local, remote));
    }


    public static QuicConnection getOrCreateConnection(DatagramChannel channel, InetSocketAddress remote) {
        if (remote == null){
            log.error("远程地址为空");
            return null;
        }
        // ========== 核心：前置校验 Channel 状态 ==========
        long connectionId = ConnectionIdGenerator.generate(channel.localAddress(), remote);
        return CONNECTION_MAP.get(connectionId, id ->
                QuicConnection.create(channel, channel.localAddress(), remote));
    }



    /**
     * 移除连接
     */
    public static void removeConnection(long connectionId) {
        QuicConnection conn = CONNECTION_MAP.asMap().remove(connectionId);
        if (conn != null) {
            conn.close();
        }
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

}
