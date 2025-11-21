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

import static com.bit.solana.p2p.impl.common.*;

@Data
@Slf4j
public class QuicNodeWrapper {
    private String nodeId; // 节点ID 公钥的base58编码
    private String address;
    private int port;
    private QuicChannel quicChannel; // 连接

    private QuicStreamChannel heartbeatStream; // 专属心跳流（复用，不销毁）
    private QuicStreamChannel businessStream; // 轻量业务复用流  适合高频小包传输，避免窗口频繁调整的开销
    private Map<String, QuicStreamChannel> tempStreams; // 临时流 hash->流

    private long lastActiveTime; // 最后活跃时间（用于过期清理）
    private boolean isOutbound; // 是否为主动出站连接
    private boolean active;

    //衍生
    private InetSocketAddress inetSocketAddress;

    // 心跳任务实例
    private ScheduledFuture<?> heartbeatTask;//心跳任务异常仅影响单个节点，不扩散（流隔离的延伸）。


    // 仅持有全局调度器引用，不自己创建
    private final ScheduledExecutorService globalScheduler;
    public QuicNodeWrapper(ScheduledExecutorService globalScheduler) {
        this.globalScheduler = Objects.requireNonNull(globalScheduler, "全局调度器不能为空");
        this.tempStreams = new ConcurrentHashMap<>(); // 提前初始化，避免null
    }
    // 禁止空构造器（避免误使用）
    private QuicNodeWrapper() {
        throw new UnsupportedOperationException("必须注入全局调度器，禁止无参构造");
    }



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
                            buf.release(); // 手动释放Buf，避免泄漏
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

    }


    // 关闭连接
    public void close() {
        // 1. 先关临时流（区块流）
        if (tempStreams != null) {
            tempStreams.values().forEach(stream -> {
                try {
                    if (stream.isActive()) {
                        stream.close().syncUninterruptibly(); // 直接关闭流，而非等待closeFuture
                    }
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

        // 5. 停止心跳任务
        if (heartbeatTask != null) {
            heartbeatTask.cancel(true);
        }
    }



    // 停止当前节点的心跳任务
    public void stopHeartbeat() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
            heartbeatTask = null;
            log.info("节点{}心跳任务已取消", nodeId);
        }
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
        QuicStreamChannel newHeartbeatStream = quicChannel.createStream(QuicStreamType.BIDIRECTIONAL,
                new QuicStreamHandler()).sync().get();
        this.heartbeatStream = newHeartbeatStream;
        log.info("Node {} heartbeat stream recreated, streamId: {}", nodeId, newHeartbeatStream.streamId());
        return newHeartbeatStream;
    }

    public QuicStreamChannel getOrCreateBusinessStream() throws InterruptedException, ExecutionException {
        if (businessStream != null && businessStream.isActive()) {
            return businessStream;
        }
        if (quicChannel == null || !quicChannel.isActive()) {
            log.error("Node {} QUIC connection inactive, cannot create business stream", nodeId);
            return null;
        }
        QuicStreamChannel newBusinessStream = quicChannel.createStream(QuicStreamType.BIDIRECTIONAL,
                new QuicStreamHandler()).sync().get();
        this.businessStream = newBusinessStream;
        log.info("Node {} business stream recreated, streamId: {}", nodeId, newBusinessStream.streamId());
        return newBusinessStream;
    }

    /**
     * 使用临时流要显示关闭 否则会内存泄漏
     * @param tempKey
     * @return
     * @throws InterruptedException
     * @throws ExecutionException
     */
    public QuicStreamChannel createTempStream(String tempKey) throws InterruptedException, ExecutionException {
        // 1. 先递增计数，再判断（CAS保证原子性）
        int currentCount = TEMP_STREAM_TASK_COUNT.incrementAndGet();
        if (currentCount > MAX_TEMP_STREAM_TASKS) {
            TEMP_STREAM_TASK_COUNT.decrementAndGet(); // 回退计数
            log.error("Temp stream tasks reach max limit: {}, reject create stream for node {}", MAX_TEMP_STREAM_TASKS, nodeId);
            return null;
        }

        try {
            if (quicChannel == null || !quicChannel.isActive()) {
                log.error("Node {} QUIC connection inactive, cannot create block sync stream", nodeId);
                return null;
            }
            QuicStreamChannel tempStream = quicChannel.createStream(QuicStreamType.UNIDIRECTIONAL,
                    new QuicStreamHandler()).sync().get();
            if (tempStreams == null) {
                tempStreams = new ConcurrentHashMap<>();
            }
            tempStreams.put(tempKey, tempStream);
            log.info("Node {} create block sync stream, blockId: {}, streamId: {}", nodeId, tempKey, tempStream.streamId());

            // 超时自动关闭任务
            ScheduledFuture<?> timeoutTask = globalScheduler.schedule(() -> {
                if (tempStream.isActive()) {
                    log.warn("Node {} temp stream {} timeout ({}s), close automatically", nodeId, tempKey, TEMP_STREAM_TIMEOUT_SECONDS);
                    try {
                        tempStream.close().syncUninterruptibly();
                    } catch (Exception e) {
                        log.error("Node {} close timeout temp stream {} failed", nodeId, tempKey, e);
                    }
                }
            }, TEMP_STREAM_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            tempStream.closeFuture().addListener(future -> {
                try {
                    if (tempStreams != null) {
                        tempStreams.remove(tempKey);
                    }
                    timeoutTask.cancel(false);
                    TEMP_STREAM_TASK_COUNT.decrementAndGet(); // 关闭后递减计数
                    log.debug("Node {} temp stream {} closed, removed from map", nodeId, tempKey);
                } catch (Exception e) {
                    log.error("Node {} remove temp stream {} failed", nodeId, tempKey, e);
                    if (tempStreams != null) {
                        tempStreams.remove(tempKey);
                    }
                    TEMP_STREAM_TASK_COUNT.decrementAndGet(); // 异常也要递减
                }
            });
            return tempStream;
        } catch (Exception e) {
            TEMP_STREAM_TASK_COUNT.decrementAndGet(); // 创建失败回退计数
            throw e;
        }
    }


    // 更新活跃时间
    public void updateActiveTime() {
        this.lastActiveTime = System.currentTimeMillis();
    }


    // 检查连接是否活跃（通道活跃+未过期）
    public boolean isActive() {
        boolean channelActive = quicChannel != null && quicChannel.isActive();
        boolean heartbeatActive = heartbeatStream != null && heartbeatStream.isActive();
        boolean notExpired = System.currentTimeMillis() - lastActiveTime < NODE_EXPIRATION_TIME * 1000;
        // 核心：心跳流活 + 通道活 + 未过期，业务流可重建
        return channelActive && heartbeatActive && notExpired;
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

    @Override
    protected void finalize() throws Throwable {
        try {
            if (tempStreams != null && !tempStreams.isEmpty()) {
                log.warn("Node {} finalize called, temp streams not cleared, close now", nodeId);
                tempStreams.values().forEach(stream -> {
                    try { if (stream.isActive()) stream.close().syncUninterruptibly(); }
                    catch (Exception e) { log.error("Finalize close temp stream failed", e); }
                });
                tempStreams.clear();
            }
        } finally {
            super.finalize();
        }
    }

}
