package com.bit.solana.quic;


import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timer;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

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
        QuicFrame reqQuicFrame = QuicFrame.acquire();//已经释放
        reqQuicFrame.setConnectionId(conId);//生成连接ID
        reqQuicFrame.setDataId(dataId);
        reqQuicFrame.setTotal(0);
        reqQuicFrame.setFrameType(QuicFrameEnum.CONNECT_REQUEST_FRAME.getCode());
        reqQuicFrame.setFrameTotalLength(QuicFrame.FIXED_HEADER_LENGTH);
        reqQuicFrame.setPayload(null);
        reqQuicFrame.setRemoteAddress(remoteAddress);
        QuicFrame resQuicFrame = sendFrame(reqQuicFrame);
        log.info("响应帧{}",resQuicFrame);
        if (resQuicFrame != null){
            //创建一个主动出站连接
            QuicConnection quicConnection = new QuicConnection();
            quicConnection.setConnectionId(conId);
            quicConnection.setRemoteAddress(remoteAddress);
            quicConnection.setUDP(true);
            quicConnection.setOutbound(true);
            quicConnection.setExpired(false);
            quicConnection.setLastSeen(System.currentTimeMillis());
            quicConnection.startHeartbeat();

            addConnection(conId, quicConnection);
            resQuicFrame.release();
            return quicConnection;
        }
        return null;
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
     * 获取第一个连接
     */
    public static QuicConnection getFirstConnection() {
        return CONNECTION_MAP.asMap().values().stream().findFirst().orElse(null);
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
            quicConnection.startHeartbeat();
            QuicConnectionManager.addConnection(connectionId, quicConnection);
        }
        quicConnection.updateLastSeen();
        return quicConnection;
    }




    /**
     * 发送连接请求帧（核心实现）
     * 本地错误：20ms重试1次，最多3次；网络错误：100ms重试1次，最多3次；失败返回null
     */
    public static QuicFrame sendFrame(QuicFrame quicFrame) {
        try {
            // 1. 基础参数定义
            final int LOCAL_RETRY_MAX = 3;    // 本地错误最大重试次数
            final long LOCAL_RETRY_INTERVAL = 20; // 本地重试间隔（ms）
            final int NET_RETRY_MAX = 2;      // 网络错误最大重试次数
            final long NET_RETRY_INTERVAL = 50;  // 网络重试间隔（ms）
            final long RESPONSE_TIMEOUT = 150;   // 单次响应等待超时（ms）
            InetSocketAddress remoteAddress = quicFrame.getRemoteAddress();
            long connectionId = quicFrame.getConnectionId();

            long dataId = quicFrame.getDataId();
            CompletableFuture<Object> responseFuture = new CompletableFuture<>();
            RESPONSE_FUTURECACHE.put(dataId, responseFuture);

            // 2. 网络错误重试循环（外层：处理端到端无响应）
            for (int netRetry = 0; netRetry < NET_RETRY_MAX; netRetry++) {
                // 每次网络重试生成新的dataId（避免旧响应干扰）

                // 3. 本地错误重试（内层：确保数据能发出去）
                AtomicInteger localRetryCount = new AtomicInteger(0);
                boolean localSendSuccess = sendWithLocalRetry(quicFrame, localRetryCount, LOCAL_RETRY_MAX, LOCAL_RETRY_INTERVAL);

                if (!localSendSuccess) {
                    log.error("[请求] 连接ID:{} 远程地址:{} 本地发送重试{}次失败，终止网络重试",
                            connectionId, remoteAddress, LOCAL_RETRY_MAX);
                    RESPONSE_FUTURECACHE.asMap().remove(dataId);
                    break;
                }


                // 4. 本地发送成功，等待响应（判定网络错误）
                try {
                    // 等待响应，超时则触发下一次网络重试
                    Object result = responseFuture.get(RESPONSE_TIMEOUT, TimeUnit.MILLISECONDS);
                    if (result instanceof QuicFrame responseFrame) {
                        log.info("[请求] 连接ID:{} 远程地址:{} 第{}次网络重试成功，收到响应",
                                connectionId, remoteAddress, netRetry );
                        // 清理缓存，返回响应帧
                        RESPONSE_FUTURECACHE.asMap().remove(dataId);
                        return responseFrame;
                    }
                } catch (Exception e) {
                    // 响应超时/异常 → 网络错误，准备下一次重试
                    log.warn("[请求] 连接ID:{} 远程地址:{} 第{}次网络重试超时（{}ms），原因：{}",
                            connectionId, remoteAddress, netRetry + 1, RESPONSE_TIMEOUT, e.getMessage());
                    RESPONSE_FUTURECACHE.asMap().remove(dataId);

                    // 非最后一次网络重试 → 等待间隔后重试
                    if (netRetry < NET_RETRY_MAX - 1) {
                        try {
                            TimeUnit.MILLISECONDS.sleep(NET_RETRY_INTERVAL);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            log.error("[请求] 网络重试间隔被中断", ie);
                            break;
                        }
                    }
                }
            }

            // 所有重试耗尽，返回null
            log.error("[请求] 连接ID:{} 远程地址:{} 本地/网络重试均失败，返回null",
                    quicFrame.getConnectionId(), remoteAddress);
            return null;
        }finally {
            quicFrame.release();
        }
    }

    /**
     * 本地发送重试工具方法（处理本地错误，20ms间隔重试）
     * @param frame 待发送帧
     * @param retryCount 已重试次数（原子类，避免并发问题）
     * @param maxRetry 最大重试次数
     * @param interval 重试间隔（ms）
     * @return 本地发送是否成功
     */
    private static boolean sendWithLocalRetry(QuicFrame frame, AtomicInteger retryCount, int maxRetry, long interval) {
        // 递归出口：重试次数耗尽
        if (retryCount.get() >= maxRetry) {
            return false;
        }

        // 构建发送数据包
        ByteBuf buf = null;
        try {
            buf = ALLOCATOR.buffer();
            frame.encode(buf);
            DatagramPacket packet = new DatagramPacket(buf, frame.getRemoteAddress());
            // 同步发送（阻塞直到发送完成）
            ChannelFuture future = Global_Channel.writeAndFlush(packet).sync();


            if (future.isSuccess()) {
                log.info("[本地发送成功] 数据ID:{} 连接ID:{} 重试次数:{}",
                        frame.getDataId(), frame.getConnectionId(), retryCount.get());
                return true;
            } else {
                // 本地发送失败，重试
                retryCount.incrementAndGet();
                log.warn("[本地发送失败] 数据ID:{} 连接ID:{} 重试次数:{}，原因：{}",
                        frame.getDataId(), frame.getConnectionId(), retryCount.get(), future.cause().getMessage());

                // 等待重试间隔后递归重试
                TimeUnit.MILLISECONDS.sleep(interval);
                return sendWithLocalRetry(frame, retryCount, maxRetry, interval);
            }
        } catch (Exception e) {
            // 编码/发送异常 → 本地错误，重试
            retryCount.incrementAndGet();
            log.error("[本地发送异常] 数据ID:{} 连接ID:{} 重试次数:{}，原因：{}",
                    frame.getDataId(), frame.getConnectionId(), retryCount.get(), e.getMessage());

            // 释放缓冲区，避免内存泄漏
            if (buf != null) {
                buf.release();
            }

            // 等待重试间隔后递归重试
            try {
                TimeUnit.MILLISECONDS.sleep(interval);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.error("[本地重试间隔被中断]", ie);
                return false;
            }
            return sendWithLocalRetry(frame, retryCount, maxRetry, interval);
        }
    }
















}
