package com.bit.solana.p2p.impl;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.incubator.codec.quic.QuicChannel;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import io.netty.incubator.codec.quic.QuicStreamType;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;

import static com.bit.solana.p2p.impl.PeerServiceImpl.NODE_EXPIRATION_TIME;
import static com.bit.solana.p2p.protocol.P2pMessageHeader.HEARTBEAT_SIGNAL;


/**
 * 心跳流：小窗口（如 8KB），适配高频小包，减少内存占用；
 * 业务流：中等窗口（如 64KB），适配交易广播等轻量业务；
 * 区块流：大窗口（如 1MB），提升大包吞吐。
 */

/**
 * QUIC 流是轻量级操作，核心耗时几乎可忽略：
 * 无握手成本：流基于已建立的 QUIC 连接创建，无需额外网络往返（RTT=0）；
 * 状态初始化：仅需在用户态（如 Netty）分配流 ID、流控窗口（KB 级内存），无内核级资源占用；
 * 协议开销：仅需发送一个 STREAM 帧（含流 ID），数据包大小仅字节级，网络开销可忽略。
 */

@Data
@Slf4j  // 添加日志注解
public class QuicNodeWrapper {
    private String nodeId; // 节点唯一标识（公钥Hex）
    private String address;
    private int port;
    private QuicChannel quicChannel; // 连接

    // 1. 拆分核心流：心跳流（独立）+ 业务流（复用）+ 临时区块流（按需创建）
    //基于连接创建的流
    private QuicStreamChannel heartbeatStream; // 专属心跳流（复用，不销毁）
    private QuicStreamChannel businessStream; // 轻量业务复用流  适合高频小包传输，避免窗口频繁调整的开销
    private Map<byte[], QuicStreamChannel> tempStreams; // 区块同步临时流（区块Hash） 交易流  大数据（>64KB）：临时流可独立配置更大流控窗口（如 1MB），配合 QUIC 的拥塞控制算法，提升大文件吞吐，同时不影响其他流的传输。

    private long lastActiveTime; // 最后活跃时间（用于过期清理）
    private boolean isOutbound; // 是否为主动出站连接
    private boolean active;

    private InetSocketAddress inetSocketAddress;


    // 仅持有全局调度器引用，不自己创建
    private final ScheduledExecutorService globalScheduler;
    // 每个节点一个心跳任务实例（全局调度器调度）
    private ScheduledFuture<?> heartbeatTask;//心跳任务异常仅影响单个节点，不扩散（流隔离的延伸）。


    // 启动节点独有心跳任务（提交到全局调度器）
    public void startHeartbeat(long intervalSeconds) {
        // 取消原有任务（避免重复调度）
        if (heartbeatTask != null && !heartbeatTask.isCancelled()) {
            heartbeatTask.cancel(false);
        }

        // 每个节点创建独立的心跳任务（Runnable），提交到全局调度器
        this.heartbeatTask = globalScheduler.scheduleAtFixedRate(
                // 节点独有任务：检查自己的状态、发送自己的心跳
                () -> {
                    try {
                        // 1. 节点私有状态检查
                        if (!isActive()) {
                            log.warn("节点{}连接失效，停止心跳", nodeId);
                            stopHeartbeat();
                            return;
                        }
                        // 2. 节点私有流检查/重建
                        QuicStreamChannel stream = getOrCreateHeartbeatStream();
                        if (stream == null || !stream.isActive()) {
                            log.error("节点{}心跳流不可用", nodeId);
                            setActive(false);
                            return;
                        }
                        // 3. 发送节点私有心跳
                        ByteBuf buf = Unpooled.copiedBuffer(HEARTBEAT_SIGNAL);
                        stream.writeAndFlush(buf).addListener(future -> {
                            if (future.isSuccess()) {
                                updateActiveTime();
                                log.debug("节点{}心跳发送成功", nodeId);
                            } else {
                                log.error("节点{}心跳发送失败", nodeId, future.cause());
                                setActive(false);
                            }
                        });
                    } catch (Exception e) {
                        log.error("节点{}心跳任务异常", nodeId, e);
                        setActive(false);
                    }
                },
                0, // 初始延迟0秒
                intervalSeconds, // 间隔（节点可自定义）
                TimeUnit.SECONDS
        );
        log.info("节点{}心跳任务已提交到全局调度器，间隔{}秒", nodeId, intervalSeconds);
    }


    // 停止当前节点的心跳任务
    public void stopHeartbeat() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
            heartbeatTask = null;
            log.info("节点{}心跳任务已取消", nodeId);
        }
    }


    // 必须通过构造器注入全局调度器（强制依赖，避免误创建）
    public QuicNodeWrapper(ScheduledExecutorService globalScheduler) {
        this.globalScheduler = Objects.requireNonNull(globalScheduler, "全局调度器不能为空");
    }

    // 禁止空构造器（避免误使用）
    private QuicNodeWrapper() {
        throw new UnsupportedOperationException("必须注入全局调度器，禁止无参构造");
    }


    // 检查连接是否活跃（通道活跃+未过期）
    public boolean isActive() {
        boolean channelActive = quicChannel != null && quicChannel.isActive();
        boolean heartbeatActive = heartbeatStream != null && heartbeatStream.isActive();
        boolean notExpired = System.currentTimeMillis() - lastActiveTime < NODE_EXPIRATION_TIME * 1000;
        // 核心：心跳流活 + 通道活 + 未过期，业务流可重建
        return channelActive && heartbeatActive && notExpired;
    }


    // 更新活跃时间
    public void updateActiveTime() {
        this.lastActiveTime = System.currentTimeMillis();
    }

    // 关闭连接
    public void close() {
        // 1. 先关临时流（区块流）
        if (tempStreams != null) {
            tempStreams.values().forEach(stream -> {
                try {
                    //改用 syncUninterruptibly() 避免中断，且逐个关闭并捕获异常
                    if (stream.isActive()) stream.closeFuture().sync().syncUninterruptibly();
                } catch (Exception e) {
                    log.error("Close block sync stream failed for node: {}", nodeId, e);
                }
            });
            tempStreams.clear();
        }
        // 2. 再关业务流
        try {
            if (businessStream != null && businessStream.isActive()) {
                businessStream.closeFuture().sync();
            }
        } catch (Exception e) {
            log.error("Close business stream failed for node: {}", nodeId, e);
        }
        // 3. 最后关心跳流（确保最后检测节点存活）
        try {
            if (heartbeatStream != null && heartbeatStream.isActive()) {
                heartbeatStream.closeFuture().sync();
            }
        } catch (Exception e) {
            log.error("Close heartbeat stream failed for node: {}", nodeId, e);
        }
        // 4. 关QUIC通道
        try {
            if (quicChannel != null && quicChannel.isActive()) {
                quicChannel.closeFuture().sync();
            }
        } catch (Exception e) {
            log.error("Close QUIC channel failed for node: {}", nodeId, e);
        }
        //关闭心跳
        stopHeartbeat();
    }

    /**
     * 设置节点活跃状态
     * @param active 活跃状态
     */
    public void setActive(boolean active) {
        // 状态未变化时直接返回，避免无效操作
        if (this.active == active) {
            return;
        }
        // 更新状态
        this.active = active;
        if (active) {
            // 激活时更新最后活跃时间
            updateActiveTime();
        } else {
            // 非活跃时关闭连接（可选，根据业务需求调整）
            // 如果业务需要保留通道但标记为非活跃，可注释下面这行
            close();
        }
    }


    public InetSocketAddress getInetSocketAddress() {
        if (inetSocketAddress == null){
            inetSocketAddress = new InetSocketAddress(address, port);
        }
        return inetSocketAddress;
    }




    // 2. 心跳流专属创建方法（独立复用）
    public QuicStreamChannel getOrCreateHeartbeatStream() throws InterruptedException, ExecutionException {
        if (heartbeatStream != null && heartbeatStream.isActive()) {
            return heartbeatStream;
        }
        if (quicChannel == null || !quicChannel.isActive()) {
            log.error("Node {} QUIC connection inactive, cannot create heartbeat stream", nodeId);
            return null;
        }
        // 心跳流用双向流，配置更小的缓冲区（适配小包）
/*        QuicStreamChannel newHeartbeatStream = quicChannel.createStream(QuicStreamType.BIDIRECTIONAL,
                new HeartbeatStreamHandler()).sync().getNow();*///若异步未完成，返回null


        QuicStreamChannel newHeartbeatStream = quicChannel.createStream(QuicStreamType.BIDIRECTIONAL,
                new ServiceQuicStreamHandler()).sync().get();//get() 等待异步完成（sync()已保证，get()是安全的）
        this.heartbeatStream = newHeartbeatStream;
        log.info("Node {} heartbeat stream recreated, streamId: {}", nodeId, newHeartbeatStream.streamId());
        return newHeartbeatStream;
    }


    // 3. 业务流复用方法（轻量业务）
    public QuicStreamChannel getOrCreateBusinessStream() throws InterruptedException, ExecutionException {
        if (businessStream != null && businessStream.isActive()) {
            return businessStream;
        }
        if (quicChannel == null || !quicChannel.isActive()) {
            log.error("Node {} QUIC connection inactive, cannot create business stream", nodeId);
            return null;
        }
        QuicStreamChannel newBusinessStream = quicChannel.createStream(QuicStreamType.BIDIRECTIONAL,
                new ServiceQuicStreamHandler()).sync().get();
        this.businessStream = newBusinessStream;
        log.info("Node {} business stream recreated, streamId: {}", nodeId, newBusinessStream.streamId());
        return newBusinessStream;
    }

    // 4. 临时流（不复用，按需创建/销毁）
    public QuicStreamChannel createTempStream(byte[] tempKey) throws InterruptedException, ExecutionException {
        if (quicChannel == null || !quicChannel.isActive()) {
            log.error("Node {} QUIC connection inactive, cannot create block sync stream", nodeId);
            return null;
        }
        // 区块流用单向流（下载区块仅需读，上传仅需写），减少双向流开销
        QuicStreamChannel blockStream = quicChannel.createStream(QuicStreamType.UNIDIRECTIONAL,
                new ServiceQuicStreamHandler()).sync().get();
        if (tempStreams == null) {
            tempStreams = new ConcurrentHashMap<>();
        }
        tempStreams.put(tempKey, blockStream);
        log.info("Node {} create block sync stream, blockId: {}, streamId: {}", nodeId, tempKey, blockStream.streamId());
        // 注册流关闭回调，自动清理
        blockStream.closeFuture().addListener(future -> tempStreams.remove(tempKey));
        //超时
        return blockStream;
    }



    /**
     * // 创建心跳流时配置流控窗口
     * QuicStreamChannel newHeartbeatStream = quicChannel.createStream(QuicStreamType.BIDIRECTIONAL,
     *         new HeartbeatStreamHandler())
     *         .sync()
     *         .getNow();
     * // 设置流控窗口（Netty QUIC 配置）
     * newHeartbeatStream.config().setOption(QuicChannelOption.QUIC_STREAM_RECEIVE_BUFFER_SIZE, 8 * 1024);
     * newHeartbeatStream.config().setOption(QuicChannelOption.QUIC_STREAM_SEND_BUFFER_SIZE, 8 * 1024);
     */

}