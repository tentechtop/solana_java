package com.bit.solana.p2p.quic;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.socket.DatagramChannel;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 连接管理器（全局连接池）
 */
@Slf4j
public class QuicConnectionManager {
    private static final Map<Long, QuicConnection> CONNECTION_MAP = new ConcurrentHashMap<>();




    public static QuicConnection getOrCreateConnection(DatagramChannel channel, InetSocketAddress local,
                                                              InetSocketAddress remote, QuicMetrics metrics) {
        long connectionId = ConnectionIdGenerator.generate(local, remote);
        log.info("获取或创建连接1:{}", connectionId);
        channel.connect(remote);
        return CONNECTION_MAP.computeIfAbsent(connectionId, id ->
                new QuicConnection(id, channel, local, remote, metrics));
    }

    public static QuicConnection getOrCreateConnection(DatagramChannel channel,
                                                       InetSocketAddress remote) {
        // ========== 核心：前置校验 Channel 状态 ==========
        if (channel == null) {
            throw new IllegalArgumentException("DatagramChannel 不能为空");
        }
        if (!channel.isOpen()) {
            throw new IllegalStateException("DatagramChannel 已关闭，无法执行 connect 操作");
        }
        if (!channel.isActive()) {
            log.warn("DatagramChannel 未激活（可能未 bind 本地端口），远程地址：{}", remote);
            // 若 Channel 未激活，尝试先 bind（UDP 必须绑定本地端口才能收发）
            try {
                ChannelFuture bindFuture = channel.bind(new InetSocketAddress(0)); // 随机端口
                bindFuture.sync();
                if (!bindFuture.isSuccess()) {
                    throw new RuntimeException("Channel 绑定本地端口失败", bindFuture.cause());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Channel bind 被中断", e);
            }
        }

        long connectionId = ConnectionIdGenerator.generate(channel.localAddress(), remote);
        log.info("获取或创建连接2:{}", connectionId);

        // ========== 可选：若仍需 connect，增加异常捕获 ==========
        ChannelFuture connectFuture = channel.connect(remote);
        try {
            connectFuture.sync();
            if (!connectFuture.isSuccess()) {
                // 捕获 Channel 关闭导致的异常

                log.error("UDP Channel 设置默认远程地址失败:{}", remote, connectFuture.cause());
                throw new RuntimeException("connect 失败", connectFuture.cause());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("connect 被中断", e);
        }

        return CONNECTION_MAP.computeIfAbsent(connectionId, id ->
                new QuicConnection(id, channel, channel.localAddress(), remote, new QuicMetrics()));
    }

    /**
     * 移除连接
     */
    public static void removeConnection(long connectionId) {
        QuicConnection conn = CONNECTION_MAP.remove(connectionId);
        if (conn != null) {
            conn.close();
        }
    }

    /**
     * 获取连接
     */
    public static QuicConnection getConnection(long connectionId) {
        return CONNECTION_MAP.get(connectionId);
    }

    /**
     * 获取连接数
     */
    public static int getConnectionCount() {
        return CONNECTION_MAP.size();
    }

}
