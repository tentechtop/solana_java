package com.bit.solana.p2p.impl;

import com.github.benmanes.caffeine.cache.RemovalCause;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Field;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;


@Slf4j
@Component
public class Common {
    //临时流超时时间
    public static final long TEMP_STREAM_TIMEOUT_SECONDS = 30;
    // 消息过期时间
    public static final long MESSAGE_EXPIRATION_TIME = 30;//秒
    // 节点过期时间
    public static final long NODE_EXPIRATION_TIME = 60;//秒

    // 心跳消息 ping
    public static final byte[] HEARTBEAT_PING_SIGNAL = new byte[]{0x00, 0x01};
    // 心跳消息 pong
    public static final byte[] HEARTBEAT_ping_SIGNAL = new byte[]{0x00, 0x02};


    /**
     * 全局调度器：固定4个核心线程，轻量处理定时任务（监控、清理、心跳等）
     * DelayedWorkQueue（Java 内置），这个队列本身就是无界的（没有固定容量上限，理论上可无限添加任务，直到 JVM 内存耗尽）；
     * 你的场景是「心跳、监控、简单定时任务」，核心特点是：
     * 任务逻辑简单（无复杂业务、无大量计算）；
     * 任务量小（心跳 / 监控通常每分钟 / 几秒一次，队列不会堆积）；
     * 无严格的 “资源泄漏” 风险（心跳 / 监控任务无持有的重量级资源）；
     */
    public static final ScheduledExecutorService GLOBAL_SCHEDULER = Executors.newScheduledThreadPool(4, r -> {
        Thread t = new Thread(r, "global-scheduler");
        t.setDaemon(true); // 守护线程，避免阻塞应用退出
        return t;
    });


    /**
     * 节点连接缓存
     */
    public static Cache<String, QuicNodeWrapper> PEER_CONNECT_CACHE  = Caffeine.newBuilder()
            .maximumSize(10000)
            .expireAfterAccess(NODE_EXPIRATION_TIME, TimeUnit.SECONDS) //按访问过期，长期不活跃直接淘汰
            .recordStats()
            // 改为同步执行（避免调度器延迟），或限制监听器执行超时
            .removalListener((String nodeId, QuicNodeWrapper node, RemovalCause cause) -> {
                if (node != null) {
                    log.info("Node {} removed from cache (cause: {}), closing resources", nodeId, cause);
                    // 同步关闭，设置超时
                    try {
                        CompletableFuture.runAsync(node::close, Executors.newSingleThreadExecutor())
                                .get(5, TimeUnit.SECONDS); // 5秒超时，避免阻塞
                    } catch (Exception e) {
                        log.error("Close node {} failed in removal listener", nodeId, e);
                        node.close(); // 兜底同步关闭
                    }
                }
            })
            .build();

    /**
     * 请求响应Future缓存：最大容量100万个，30秒过期（请求超时后自动清理，避免内存泄漏）
     * Key：请求ID（UUID），Value：响应Future
     */
    public static Cache<UUID, CompletableFuture<byte[]>> RESPONSE_FUTURECACHE  = Caffeine.newBuilder()
            .maximumSize(1000_000)
            .expireAfterWrite(30, TimeUnit.SECONDS)
            .weakValues() // 弱引用存储Future，GC时可回收
            .recordStats()
            .build();

    /**
     * 最多缓存1万个节点的重连计数
     * 重连成功后重置 / 清理计数器
     */
    public final Cache<String, AtomicInteger> RECONNECT_COUNTER = Caffeine.newBuilder()
            .maximumSize(10000)
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .build();


    @PreDestroy
    public void shutdown() throws InterruptedException {
        GLOBAL_SCHEDULER.shutdown();
        log.info("关闭全局调度器");
    }


}
