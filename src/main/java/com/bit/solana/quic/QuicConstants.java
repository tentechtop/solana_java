package com.bit.solana.quic;

import com.bit.solana.util.SnowflakeIdGenerator;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.socket.DatagramChannel;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timer;

import java.util.Map;
import java.util.concurrent.*;

public class QuicConstants {
    /**
     * 请求响应Future缓存：最大容量100万个，30秒过期（请求超时后自动清理，避免内存泄漏）
     * Key：请求ID，Value：响应Future
     * 16字节的UUIDV7 - > CompletableFuture<byte[]>
     */
    public static Cache<Long, CompletableFuture<Object>> RESPONSE_FUTURECACHE  = Caffeine.newBuilder()
            .maximumSize(1000_000)
            .expireAfterWrite(5, TimeUnit.SECONDS)
            .weakValues() // 弱引用存储Future，GC时可回收
            .recordStats()
            .build();

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


    //连接下的数据
    public static Map<Long, SendQuicData> SendMap = new ConcurrentHashMap<>();//发送中的数据缓存 数据收到全部的ACK后释放掉 发送帧50ms后未收到ACK则重发 重发三次未收到ACK则放弃并清除数据 下线节点

    public static  Map<Long, ReceiveQuicData> ReceiveMap = new ConcurrentHashMap<>();//接收中的数据缓存 数据完整后释放掉

    public static final Timer GLOBAL_TIMER = new HashedWheelTimer(
            10, // 时间轮精度10ms
            java.util.concurrent.TimeUnit.MILLISECONDS,
            1024 // 时间轮槽数
    );

    // 全局超时时间（ms）：300ms
    public static final long GLOBAL_TIMEOUT_MS = 300;
    // 单帧重传间隔（ms）
    public static final long RETRANSMIT_INTERVAL_MS = 50;
    // 单帧最大重传次数（300/50=6次）
    public static final int MAX_RETRANSMIT_TIMES = 6;
}
