package com.bit.solana.p2p.impl;

import com.bit.solana.p2p.impl.handle.QuicStreamHandler;
import com.bit.solana.p2p.protocol.P2PMessage;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.incubator.codec.quic.QuicChannel;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import io.netty.incubator.codec.quic.QuicStreamType;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.concurrent.*;

import static com.bit.solana.config.CommonConfig.RESPONSE_FUTURECACHE;
import static com.bit.solana.p2p.protocol.P2PMessage.newRequestMessage;
import static com.bit.solana.p2p.protocol.ProtocolEnum.PING_V1;
import static com.bit.solana.util.ByteUtils.bytesToHex;

/**
 * 随用随建是其最优使用方式
 */

@Data
@Slf4j
public class QuicNodeWrapper {
    private byte[] nodeId; // 节点ID 公钥的base58编码
    private String address;
    private int port;
    private QuicChannel quicChannel; // 连接 一个连接 + 无数临时流
    private QuicStreamChannel heartbeatStream; // 专属心跳流（复用，不销毁） 仅仅用来保活连接 5秒一次
    private boolean isOutbound; // 是否为主动出站连接
    private boolean active;

    //最后更新时间
    private long lastSeen;
    // 连接过期阈值（秒）：默认5秒，无活动则判定为过期
    private int expireSeconds = 300;

    //衍生
    private InetSocketAddress inetSocketAddress;

    // 心跳任务实例
    private ScheduledFuture<?> heartbeatTask;//心跳任务异常仅影响单个节点，不扩散（流隔离的延伸）。


    // 仅持有全局调度器引用，不自己创建
    private final ScheduledExecutorService globalScheduler;
    public QuicNodeWrapper(ScheduledExecutorService globalScheduler) {
        this.globalScheduler = Objects.requireNonNull(globalScheduler, "全局调度器不能为空");

    }
    // 禁止空构造器（避免误使用）
    private QuicNodeWrapper() {
        throw new UnsupportedOperationException("必须注入全局调度器，禁止无参构造");
    }

    public void startHeartbeat(long intervalSeconds,byte[] localId) {
        try {
            // 1. 取消原有任务（避免重复调度）
            stopHeartbeat();
            // 3. 提交周期性心跳任务到全局调度器
            heartbeatTask = globalScheduler.scheduleAtFixedRate(
                    () -> executeHeartbeat(localId), // 心跳执行逻辑
                    0, // 初始延迟：立即执行第一次
                    intervalSeconds, // 周期间隔
                    TimeUnit.SECONDS // 时间单位
            );

            log.info("节点{}心跳任务已启动，间隔{}秒，调度器：{}",
                    bytesToHex(nodeId), intervalSeconds, globalScheduler);
        }catch (Exception e){
            throw new RuntimeException("心跳发生错误",e);
        }
    }


    /**
     * 执行单次心跳逻辑（封装为独立方法，便于调度执行）
     * @param localId 本地节点ID
     */
    private void executeHeartbeat(byte[] localId) {
        try {
            // 前置检查：连接是否活跃
            if (!isActive()) {
                log.warn("节点{}连接已失效（非活跃/过期），停止心跳任务", bytesToHex(nodeId));
                stopHeartbeat();
                return;
            }

            // 1. 获取/创建心跳流
            QuicStreamChannel heartbeatStream = getOrCreateHeartbeatStream();
            if (heartbeatStream == null || !heartbeatStream.isActive()) {
                log.error("节点{}无法获取有效心跳流，本次心跳失败", bytesToHex(nodeId));
                return;
            }

            // 2. 构建心跳消息
            P2PMessage p2PMessage = newRequestMessage(localId, PING_V1, new byte[]{0x01});
            byte[] requestId = p2PMessage.getRequestId();
            byte[] serialize = p2PMessage.serialize();

            // 3. 发送心跳请求
            ByteBuf byteBuf = Unpooled.wrappedBuffer(serialize);
            CompletableFuture<byte[]> responseFuture = new CompletableFuture<>();
            RESPONSE_FUTURECACHE.put(bytesToHex(requestId), responseFuture);

            heartbeatStream.writeAndFlush(byteBuf).addListener(future -> {
                if (!future.isSuccess()) {
                    log.error("节点{}心跳消息发送失败", bytesToHex(nodeId), future.cause());
                    // 移除缓存的future，避免内存泄漏
                    RESPONSE_FUTURECACHE.asMap().remove(bytesToHex(requestId));
                }
            });
            // 4. 等待心跳响应
            byte[] responseBytes = responseFuture.get(3, TimeUnit.SECONDS); // 响应超时设为3秒（小于心跳间隔）
            if (responseBytes == null || responseBytes.length == 0) {
                log.warn("节点{}心跳响应为空", bytesToHex(nodeId));
            } else {
                // 更新最后活动时间
                updateLastSeen();
                log.debug("节点{}心跳成功，响应长度：{}字节", bytesToHex(nodeId), responseBytes.length);
            }

        } catch (TimeoutException e) {
            log.warn("节点{}心跳响应超时（3秒），可能连接不稳定", bytesToHex(nodeId));
            // 超时不立即停止心跳，仅记录日志
        } catch (ExecutionException e) {
            log.error("节点{}心跳执行异常", bytesToHex(nodeId), e.getCause());
        } catch (InterruptedException e) {
            log.warn("节点{}心跳任务被中断", bytesToHex(nodeId));
            Thread.currentThread().interrupt(); // 恢复中断状态
            stopHeartbeat(); // 中断后停止心跳任务
        } catch (Exception e) {
            log.error("节点{}心跳发生未知异常", bytesToHex(nodeId), e);
        }
    }



    // 关闭连接
    public void close() {
        try {
            if (heartbeatStream != null && heartbeatStream.isActive()) {
                heartbeatStream.closeFuture().sync();
            }
        } catch (Exception e) {
            log.error("Close heartbeat stream failed for node: {}", nodeId, e);
        }
        try {
            if (quicChannel != null && quicChannel.isActive()) {
                quicChannel.closeFuture().sync();
            }
        } catch (Exception e) {
            log.error("Close QUIC channel failed for node: {}", nodeId, e);
        }
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

    public QuicStreamChannel getOrCreateHeartbeatStream() throws InterruptedException, ExecutionException {
        if (heartbeatStream != null && heartbeatStream.isActive()) {
            return heartbeatStream;
        }
        // 2. 关闭旧流（关键：避免泄漏）
        if (heartbeatStream != null) {
            try {
                heartbeatStream.close().sync();
            } catch (Exception e) {
                log.warn("Close old heartbeat stream failed for node: {}", nodeId, e);
            }
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

    public QuicStreamChannel createTempStream() throws InterruptedException, ExecutionException {
        if (quicChannel == null || !quicChannel.isActive()) {
            log.error("Node {} QUIC connection inactive, cannot create block sync stream", nodeId);
            return null;
        }
        return quicChannel.createStream(QuicStreamType.UNIDIRECTIONAL,
                new QuicStreamHandler()).sync().get();
    }

    // 检查连接是否活跃（通道活跃+未过期）
    public boolean isActive() {
        boolean channelActive = quicChannel != null && quicChannel.isActive();
        if (!channelActive) {
            log.warn("节点{}已断开连接", nodeId);
            return false;
        }
        // 2. 再检查是否过期（核心：结合lastSeen判断）
        return !isExpired();
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
        if (!active){
            close();
        }
    }

    public InetSocketAddress getInetSocketAddress() {
        if (inetSocketAddress == null){
            inetSocketAddress = new InetSocketAddress(address, port);
        }
        return inetSocketAddress;
    }

    /**
     * 检查连接是否过期
     * @return true=过期，false=未过期
     */
    public boolean isExpired() {
        // 无最后更新时间 → 视为过期
        if (lastSeen <= 0) {
            return true;
        }
        // 计算超时时间：当前时间 - 最后更新时间 > 过期阈值（毫秒）
        long expireMillis = expireSeconds * 1000L;
        boolean b = (System.currentTimeMillis() - lastSeen) > expireMillis;
        log.info("节点{}已过期", b);
        return b;
    }

    /**
     * 更新最后活动时间（线程安全）
     */
    public void updateLastSeen() {
        this.lastSeen = System.currentTimeMillis();
    }
}
