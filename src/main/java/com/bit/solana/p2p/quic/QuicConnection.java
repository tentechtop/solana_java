package com.bit.solana.p2p.quic;

import io.netty.channel.socket.DatagramChannel;
import io.netty.util.Timeout;
import lombok.Data;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
    private QuicMetrics metrics;// 连接监控



}
