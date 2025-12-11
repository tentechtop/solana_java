package com.bit.solana.quic;

import com.bit.solana.util.SnowflakeIdGenerator;
import io.netty.buffer.ByteBufAllocator;
import io.netty.util.HashedWheelTimer;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class QuicConstants {
    public static SnowflakeIdGenerator generator = new SnowflakeIdGenerator();

    // 基础配置 带宽耗时 = 总数据量 ÷ 实际吞吐量 = 4.35MB ÷ 115MB/s ≈ 0.0378秒 ≈ 38毫秒
    // 每一个帧最大负载1024个字节
    // Solana公网最优单帧负载（1024+256）
    public static final int MAX_FRAME_PAYLOAD = 1024;

    // 一次数据发送最大 1000个帧
    public static final int PUBLIC_BATCH_SIZE = 1000;

    public static final long CONNECTION_IDLE_TIMEOUT = 30_000; // 连接空闲超时（30s）



    // 发送方重传常量
    public static final int SEND_RETRY_INTERVAL = 50;   // 重传间隔(ms)
    public static final int SEND_MAX_RETRY = 3;        // 最大重传次数
    public static final int SEND_TIMEOUT_TOTAL = SEND_RETRY_INTERVAL * SEND_MAX_RETRY; // 总超时150ms

    // 接收方索取常量
    public static final int RECEIVE_CHECK_INTERVAL = 20; // 缺失检测间隔(ms)
    public static final int RECEIVE_MAX_REQUEST = 5;     // 最大索取次数


    // 全局虚拟线程池（复用，避免重复初始化）
    public static final ExecutorService VIRTUAL_THREAD_POOL = Executors.newVirtualThreadPerTaskExecutor();


    // 对象池
    public static final ByteBufAllocator ALLOCATOR = ByteBufAllocator.DEFAULT;
    public static final HashedWheelTimer TIMER = new HashedWheelTimer(10, TimeUnit.MILLISECONDS);

    private QuicConstants() {}

}
