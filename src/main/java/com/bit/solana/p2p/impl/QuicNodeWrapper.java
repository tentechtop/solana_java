package com.bit.solana.p2p.impl;

import io.netty.channel.Channel;
import io.netty.incubator.codec.quic.QuicChannel;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;

import static com.bit.solana.p2p.impl.PeerServiceImpl.NODE_EXPIRATION_TIME;

@Data
@Slf4j  // 添加日志注解
public class QuicNodeWrapper {
    private String nodeId; // 节点唯一标识（公钥Hex）
    private InetSocketAddress remoteAddr; // 节点地址
    private Channel channel;
    private QuicChannel quicChannel; // QUIC连接通道
    private QuicStreamChannel bidirectionalStream; // 复用双向流（心跳+业务）
    private long lastActiveTime; // 最后活跃时间（用于过期清理）
    private boolean isOutbound; // 是否为主动出站连接
    private boolean active;

    // 检查连接是否活跃（通道活跃+未过期）
    public boolean isActive() {
        // 增加空指针防护
        if (quicChannel == null || bidirectionalStream == null) {
            return false;
        }
        boolean channelActive = quicChannel.isActive() && bidirectionalStream.isActive();
        boolean notExpired = System.currentTimeMillis() - lastActiveTime < NODE_EXPIRATION_TIME * 1000;
        return channelActive && notExpired;
    }

    // 更新活跃时间
    public void updateActiveTime() {
        this.lastActiveTime = System.currentTimeMillis();
    }

    // 关闭连接
    public void close() {
        // 增加空指针防护
        try {
            if (bidirectionalStream != null && bidirectionalStream.isActive()) {
                bidirectionalStream.close();
                log.debug("Closed bidirectional stream for node: {}, remote: {}", nodeId, remoteAddr);
            }
        } catch (Exception e) {
            log.error("Failed to close bidirectional stream for node: {}", nodeId, e);
        }

        try {
            if (quicChannel != null && quicChannel.isActive()) {
                quicChannel.close();
                log.debug("Closed QUIC channel for node: {}, remote: {}", nodeId, remoteAddr);
            }
        } catch (Exception e) {
            log.error("Failed to close QUIC channel for node: {}", nodeId, e);
        }
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

        // 记录状态变更日志
        String nodeInfo = String.format("nodeId: %s, remote: %s, outbound: %s",
                nodeId, remoteAddr, isOutbound);
        if (active) {
            log.info("Node marked as active - {}", nodeInfo);
            // 激活时更新最后活跃时间
            updateActiveTime();
        } else {
            log.warn("Node marked as inactive - {}", nodeInfo);
            // 非活跃时关闭连接（可选，根据业务需求调整）
            // 如果业务需要保留通道但标记为非活跃，可注释下面这行
            close();
        }

        // 可选：触发状态变更回调（如果需要）
        // onActiveStateChanged(active);
    }

    // 可选：状态变更回调方法（如需扩展）
    /*
    private void onActiveStateChanged(boolean active) {
        // 可以在这里添加状态变更的额外处理逻辑
        // 比如通知监听器、更新统计信息等
    }
    */
}