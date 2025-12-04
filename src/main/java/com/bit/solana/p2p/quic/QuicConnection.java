package com.bit.solana.p2p.quic;

import io.netty.buffer.ByteBuf;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.Timeout;
import io.netty.util.TimerTask;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 基于目标网络地址创建的连接
 * QUIC连接（维护多流、心跳、连接迁移、资源回收）
 * 无队头阻塞的多路复用 单个 QUIC 连接内的多个流独立传输，一个流丢包不影响其他流
 * 可靠传输 基于包 / 流双序列号、ACK 确认、超时重传、滑动窗口（TCP 特性迁移）
 * 连接迁移 连接标识与四元组（IP:Port）解耦，基于 Connection ID 标识连接，支持 NAT 穿越
 * 灵活的拥塞控制 可插拔拥塞控制算法（CUBIC/BBR），流级 / 连接级双层流量控制
 * 低延迟握手 支持 0-RTT（复用会话）、1-RTT（首次握手）
 * 前向兼容与版本协商
 */
@Slf4j
@Data
public class QuicConnection {
    private  long connectionId;// 连接ID
    private  DatagramChannel channel;// UDP通道
    private  InetSocketAddress localAddress;// 本地地址
    private  volatile InetSocketAddress remoteAddress; // 远程地址,支持连接迁移
    private  Map<Integer, QuicStream> streamMap = new ConcurrentHashMap<>();// 流
    private  AtomicLong lastActiveTime = new AtomicLong(System.currentTimeMillis());// 最后活跃时间
    private  Timeout idleTimeout;// 空闲超时
    private  Timeout heartbeatTimeout;// 心跳超时
    private  QuicMetrics metrics;// 连接监控

    // 拥塞控制（连接级基础参数，流级细化）
    private CongestionControl congestionControl;
    // MTU适配
    private  MtuDetector mtuDetector;


    public QuicConnection(long connectionId, DatagramChannel channel, InetSocketAddress localAddress,
                                 InetSocketAddress remoteAddress, QuicMetrics metrics) {
        this.connectionId = connectionId;
        this.channel = channel;
        this.localAddress = localAddress;
        this.remoteAddress = remoteAddress;
        this.metrics = metrics;
        this.congestionControl = new CongestionControl();
        this.mtuDetector = new MtuDetector(this);

        // 空闲超时回收
        this.idleTimeout = QuicConstants.TIMER.newTimeout(new TimerTask() {
            @Override
            public void run(Timeout timeout) throws Exception {

            }
        }, QuicConstants.CONNECTION_IDLE_TIMEOUT, TimeUnit.MILLISECONDS);

        // 心跳发送
        this.heartbeatTimeout = QuicConstants.TIMER.newTimeout(new TimerTask() {
            @Override
            public void run(Timeout timeout) throws Exception {
                if (isActive()) {
                    sendHeartbeat();
                    // 重新调度
                    QuicConstants.TIMER.newTimeout(this, QuicConstants.HEARTBEAT_INTERVAL, TimeUnit.MILLISECONDS);
                }
            }
        }, QuicConstants.HEARTBEAT_INTERVAL, TimeUnit.MILLISECONDS);

        metrics.incrementConnectionCount();
    }

    public void handleFrame(QuicFrame frame) {
        // 更新最后活动时间
        lastActiveTime.set(System.currentTimeMillis());

        // 连接迁移检测
        if (!frame.getRemoteAddress().equals(remoteAddress)) {
            updateRemoteAddress(frame.getRemoteAddress());
        }
        switch (frame.getFrameType()) {
            case QuicConstants.FRAME_TYPE_DATA:
                getOrCreateStream(frame.getStreamId()).handleDataFrame(frame);
                break;
            case QuicConstants.FRAME_TYPE_ACK:
                getOrCreateStream(frame.getStreamId()).handleAckFrame(frame);
                break;
            case QuicConstants.FRAME_TYPE_HEARTBEAT:
                // 心跳响应（直接回复）
                sendHeartbeat();
                break;
            case QuicConstants.FRAME_TYPE_STREAM_CREATE:
                createStream(frame.getStreamId(), frame.getPriority(), frame.getQpsLimit());
                break;
            case QuicConstants.FRAME_TYPE_STREAM_CLOSE:
                removeStream(frame.getStreamId());
                break;
            case QuicConstants.FRAME_TYPE_FEC:
                getOrCreateStream(frame.getStreamId()).handleFecFrame(frame);
                break;
            case QuicConstants.FRAME_TYPE_MTU_DETECT:
                mtuDetector.handleMtuDetectFrame(frame);
                break;
            default:
                metrics.incrementInvalidFrameCount();
                break;
        }
        // 释放帧
        frame.release();
    }


    /**
     * 获取流（不存在则创建默认流）
     */
    public QuicStream getOrCreateStream(int streamId) {
        return streamMap.computeIfAbsent(streamId, id -> new QuicStream(this, id, (byte) 3, 0, metrics));
    }

    /**
     * 创建新流
     */
    public QuicStream createStream(int streamId, byte priority, long qpsLimit) {
        QuicStream stream = new QuicStream(this, streamId, priority, qpsLimit, metrics);
        streamMap.put(streamId, stream);
        metrics.incrementStreamCount();
        // 发送流创建帧
        sendStreamCreateFrame(streamId, priority, qpsLimit);
        return stream;
    }


    /**
     * 处理连接迁移（更新远程地址）
     */
    public void updateRemoteAddress(InetSocketAddress newRemote) {
        this.remoteAddress = newRemote;
        metrics.recordConnectionMigration(connectionId);
    }

    public CongestionControl getCongestionControl() {
        return congestionControl;
    }

    public boolean isActive() {
        return channel.isActive() && System.currentTimeMillis() - lastActiveTime.get() < QuicConstants.CONNECTION_IDLE_TIMEOUT;
    }

    /**
     * 发送心跳帧
     */
    private void sendHeartbeat() {
        QuicFrame frame = QuicFrame.acquire();
        frame.setConnectionId(connectionId);
        frame.setStreamId(-1); // 心跳帧流ID为-1
        frame.setFrameType(QuicConstants.FRAME_TYPE_HEARTBEAT);
        frame.setRemoteAddress(remoteAddress);
        sendFrame(frame);
    }


    /**
     * 发送流创建帧
     */
    private void sendStreamCreateFrame(int streamId, byte priority, long qpsLimit) {
        QuicFrame frame = QuicFrame.acquire();
        frame.setConnectionId(connectionId);
        frame.setStreamId(streamId);
        frame.setFrameType(QuicConstants.FRAME_TYPE_STREAM_CREATE);
        frame.setPriority(priority);
        frame.setQpsLimit(qpsLimit);
        frame.setRemoteAddress(remoteAddress);
        sendFrame(frame);
    }

    /**
     * 移除流
     */
    public void removeStream(int streamId) {
        QuicStream stream = streamMap.remove(streamId);
        if (stream != null) {
            stream.close();
            metrics.decrementStreamCount();
        }
    }


    /**
     * 发送帧（核心发送逻辑）
     */
    public void sendFrame(QuicFrame frame) {
        // 更新最后活动时间
        lastActiveTime.set(System.currentTimeMillis());

        // 发送数据包
        //DatagramPacket packet = new DatagramPacket(buf, frame.getRemoteAddress());
        channel.writeAndFlush(frame).addListener(future -> {
            if (!future.isSuccess()) {
                metrics.incrementSendFailCount();
            }
            // 释放帧
            frame.release();
        });
        metrics.incrementTotalSendCount();
    }

    public void close() {
        // 取消定时器
        idleTimeout.cancel();
        heartbeatTimeout.cancel();
        // 关闭所有流
        streamMap.values().forEach(QuicStream::close);
        streamMap.clear();
        // 更新指标
        metrics.decrementConnectionCount();
    }
}
