package com.bit.solana.p2p.quic;

import io.netty.channel.Channel;
import io.netty.channel.socket.DatagramChannel;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 连接管理器（全局连接池）
 */
public class QuicConnectionManager {
    private static final Map<Long, QuicConnection> CONNECTION_MAP = new ConcurrentHashMap<>();




    public static QuicConnection getOrCreateConnection(DatagramChannel channel, InetSocketAddress local,
                                                              InetSocketAddress remote, QuicMetrics metrics) {
        long connectionId = ConnectionIdGenerator.generate(local, remote);
        return CONNECTION_MAP.computeIfAbsent(connectionId, id ->
                new QuicConnection(id, channel, local, remote, metrics));
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
