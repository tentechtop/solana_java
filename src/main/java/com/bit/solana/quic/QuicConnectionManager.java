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

/**
 * 连接管理器（全局连接池）
 */
@Slf4j
public class QuicConnectionManager {

    //连接
    private static final Cache<Long, QuicConnection> CONNECTION_MAP  = Caffeine.newBuilder()
            .maximumSize(10000)
            .expireAfterAccess(NODE_EXPIRATION_TIME, TimeUnit.SECONDS) //按访问过期，长期不活跃直接淘汰
            .recordStats()
            .build();

    //连接下的数据
    public static Map<Long, SendQuicData> SendMap = new ConcurrentHashMap<>();//发送中的数据缓存 数据收到全部的ACK后释放掉 发送帧50ms后未收到ACK则重发 重发三次未收到ACK则放弃并清除数据 下线节点

    public static  Map<Long, ReceiveQuicData> ReceiveMap = new ConcurrentHashMap<>();//接收中的数据缓存 数据完整后释放掉

    public static final Timer GLOBAL_TIMER = new HashedWheelTimer(
            10, // 时间轮精度10ms
            java.util.concurrent.TimeUnit.MILLISECONDS,
            1024 // 时间轮槽数
    );

    // 全局超时时间（ms）：300ms
    public static final long GLOBAL_TIMEOUT_MS = 300;
    // 单帧重传间隔（ms）
    public static final long RETRANSMIT_INTERVAL_MS = 50;
    // 单帧最大重传次数（300/50=6次）
    public static final int MAX_RETRANSMIT_TIMES = 6;


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
