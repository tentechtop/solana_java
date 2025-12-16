package com.bit.solana.quic;


import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.netty.buffer.ByteBuf;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timer;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.*;

import static com.bit.solana.config.CommonConfig.NODE_EXPIRATION_TIME;
import static com.bit.solana.quic.QuicConstants.*;

/**
 * 连接管理器（全局连接池）
 */
@Slf4j
public class QuicConnectionManager {
    public static DatagramChannel Global_Channel = null;// UDP通道

    private static final Cache<Long, QuicConnection> CONNECTION_MAP  = Caffeine.newBuilder()
            .maximumSize(10000)
            .expireAfterAccess(NODE_EXPIRATION_TIME, TimeUnit.SECONDS) //按访问过期，长期不活跃直接淘汰
            .recordStats()
            .build();


    /**
     * 与指定的节点建立连接
     * 为该连接建立心跳任务
     */
    public static QuicConnection connectRemote(InetSocketAddress remoteAddress) throws ExecutionException, InterruptedException, TimeoutException {
        // 1. 参数校验
        if (remoteAddress == null) {
            log.error("远程地址不能为空");
            return null;
        }
        long conId = generator.nextId();
        long dataId = generator.nextId();
        //给远程地址发送连接请求请求帧 等待回复 回复成功后建立连接
        QuicFrame acquire = QuicFrame.acquire();//已经释放
        acquire.setConnectionId(conId);//生成连接ID
        acquire.setDataId(dataId);
        acquire.setTotal(0);
        acquire.setFrameType(QuicFrameEnum.CONNECT_REQUEST_FRAME.getCode());
        acquire.setFrameTotalLength(QuicFrame.FIXED_HEADER_LENGTH);
        acquire.setPayload(null);
        acquire.setRemoteAddress(remoteAddress);
        CompletableFuture<Object> responseFuture = new CompletableFuture<>();
        RESPONSE_FUTURECACHE.put(dataId, responseFuture);

        ByteBuf buf = QuicConstants.ALLOCATOR.buffer();
        acquire.encode(buf);
        DatagramPacket packet = new DatagramPacket(buf, acquire.getRemoteAddress());

        Global_Channel.writeAndFlush(packet).addListener(future -> {
            acquire.release();
            if (!future.isSuccess()) {
                log.info("节点{}连接失败", remoteAddress);
            } else {
                log.info("节点{}连接成功", remoteAddress);
            }
        });
        Object result = responseFuture.get(5, TimeUnit.SECONDS);//等待返回结果
        if (result == null) {
            log.info("结束节点{}连接失败", remoteAddress);
            return null;
        }
        log.info("连接成功");
        //创建一个主动出站连接
        QuicConnection quicConnection = new QuicConnection();
        quicConnection.setConnectionId(conId);
        quicConnection.setRemoteAddress(remoteAddress);
        quicConnection.setUDP(true);
        quicConnection.setOutbound(true);
        quicConnection.setExpired(false);
        quicConnection.setLastSeen(System.currentTimeMillis());
        quicConnection.startHeartbeat();
        return quicConnection;
    }

    /**
     * 与指定远程节点断开连接
     * UDP是无连接的，停止心跳并清理资源即可
     */
    public static void disconnectRemote(InetSocketAddress remoteAddress) {

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


    public static QuicConnection createOrGetConnection(DatagramChannel channel,InetSocketAddress local, InetSocketAddress remote, long connectionId) {
        //获取或者创建一个远程连接
        QuicConnection quicConnection = QuicConnectionManager.getConnection(connectionId);
        if (quicConnection == null){
            quicConnection = new QuicConnection();
            quicConnection.setConnectionId(connectionId);
            quicConnection.setRemoteAddress(remote);
            quicConnection.setUDP(true);
            quicConnection.setOutbound(false);//非主动连接
            quicConnection.setExpired(false);
            quicConnection.setLastSeen(System.currentTimeMillis());
            QuicConnectionManager.addConnection(connectionId, quicConnection);
        }
        quicConnection.setLastSeen(System.currentTimeMillis());
        return quicConnection;
    }



    //发送数据不释放帧
    public static void sendData(QuicFrame quicFrame) {
        ByteBuf buf = QuicConstants.ALLOCATOR.buffer();
        quicFrame.encode(buf);
        DatagramPacket packet = new DatagramPacket(buf, quicFrame.getRemoteAddress());
        Global_Channel.writeAndFlush(packet).addListener(future -> {
            if (!future.isSuccess()) {
                log.info("发送帧失败{}", quicFrame);
            } else {
                log.info("发送帧成功{}", quicFrame);
            }
        });
    }



}
