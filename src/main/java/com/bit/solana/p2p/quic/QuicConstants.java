package com.bit.solana.p2p.quic;

import io.netty.buffer.ByteBufAllocator;
import io.netty.util.HashedWheelTimer;

import java.util.concurrent.TimeUnit;

public class QuicConstants {
    // 基础配置
    public static final int DEFAULT_MTU = 1400; // 应用层MTU（避免IP分片）
    public static final int MAX_FRAME_SIZE = DEFAULT_MTU - 64; // 帧数据区最大大小（预留帧头64字节）
    public static final long CONNECTION_IDLE_TIMEOUT = 30_000; // 连接空闲超时（30s）
    public static final long STREAM_IDLE_TIMEOUT = 60_000; // 流空闲超时（60s）
    public static final long HEARTBEAT_INTERVAL = 5_000; // 心跳间隔（5s）
    public static final int MAX_RETRANSMIT_TIMES = 8; // 最大重传次数
    public static final int BATCH_ACK_THRESHOLD = 8; // 批量ACK阈值
    public static final long BATCH_ACK_DELAY = 200; // 批量ACK延迟（ms）
    public static final int INITIAL_CWND = 10; // 初始拥塞窗口大小
    public static final int SSTHRESH_INIT = 64; // 慢启动阈值初始值
    public static final int FEC_REDUNDANCY_RATIO = 10; // FEC冗余比（每10个数据帧加1个冗余帧）
    public static final long RTT_SMOOTH_FACTOR = 8; // RTT平滑因子（1/8）
    public static final long RTO_MIN = 100; // 最小重传超时（ms）
    public static final long RTO_MAX = 5_000; // 最大重传超时（ms）

    // 帧类型
    public static final byte FRAME_TYPE_DATA = 0x01; // 数据帧
    public static final byte FRAME_TYPE_ACK = 0x02; // ACK帧
    public static final byte FRAME_TYPE_HEARTBEAT = 0x03; // 心跳帧
    public static final byte FRAME_TYPE_STREAM_CREATE = 0x04; // 流创建帧
    public static final byte FRAME_TYPE_STREAM_CLOSE = 0x05; // 流关闭帧
    public static final byte FRAME_TYPE_FEC = 0x06; // FEC冗余帧
    public static final byte FRAME_TYPE_MTU_DETECT = 0x07; // MTU探测帧

    // 对象池
    public static final ByteBufAllocator ALLOCATOR = ByteBufAllocator.DEFAULT;
    public static final HashedWheelTimer TIMER = new HashedWheelTimer(10, TimeUnit.MILLISECONDS);

    private QuicConstants() {}
}
