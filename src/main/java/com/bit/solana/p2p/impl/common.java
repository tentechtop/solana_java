package com.bit.solana.p2p.impl;

import com.github.benmanes.caffeine.cache.RemovalCause;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalListener;


@Slf4j
@Component
public class common {
    //临时流超时时间
    public static final long TEMP_STREAM_TIMEOUT_SECONDS = 30;
    // 消息过期时间
    public static final long MESSAGE_EXPIRATION_TIME = 30;//秒
    // 节点过期时间
    public static final long NODE_EXPIRATION_TIME = 60;//秒

    // 心跳消息
    public static final byte[] HEARTBEAT_SIGNAL = new byte[]{0x00, 0x01};
    // 业务数据消息标识
    public static final byte[] BUSINESS_DATA_SIGNAL = new byte[]{0x00, 0x02};

    public static final AtomicInteger TEMP_STREAM_TASK_COUNT = new AtomicInteger(0);
    public static final int MAX_TEMP_STREAM_TASKS = 100000; // 最大临时流超时任务数

    /**
     * 创建健壮的全局调度器：
     * 1. 核心线程数根据CPU适配，避免固定2线程瓶颈；
     * 2. 线程工厂增加异常捕获，防止任务崩溃导致线程退出；
     * 3. 拒绝策略避免任务堆积；
     * 4. 注册JVM关闭钩子，优雅关闭调度器；
     */
    public static final ScheduledExecutorService GLOBAL_SCHEDULER = createGlobalScheduler();

    /**
     * 创建健壮的全局定时调度器（适配ScheduledThreadPoolExecutor特性）
     * 通过「限流+取消清理+监控」防控任务堆积
     */
    private static ScheduledExecutorService createGlobalScheduler() {
        // 核心线程数：基于CPU核心数适配（定时任务以IO等待为主，避免线程过少导致任务排队）
        int corePoolSize = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);

        // 带异常捕获的线程工厂（避免单个任务崩溃导致线程退出）
        ThreadFactory threadFactory = new ThreadFactory() {
            private final AtomicInteger threadNum = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(() -> {
                    try {
                        r.run();
                    } catch (Throwable e) {
                        log.error("Global scheduler thread {} caught exception", Thread.currentThread().getName(), e);
                    }
                }, "global-scheduler-" + threadNum.getAndIncrement());
                t.setDaemon(true); // 守护线程，不阻塞应用退出
                // 未捕获异常兜底
                t.setUncaughtExceptionHandler((thread, e) ->
                        log.error("Uncaught exception in scheduler thread {}", thread.getName(), e));
                return t;
            }
        };

        // 创建ScheduledThreadPoolExecutor（强制使用DelayedWorkQueue，不可替换）
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(
                corePoolSize,
                threadFactory,
                new ThreadPoolExecutor.CallerRunsPolicy() // 将 AbortPolicy 改为 CallerRunsPolicy，极端情况下由调用方执行超时任务，避免流无超时关闭：
        );

        // 关键优化1：取消的任务立即从队列移除（避免无效任务堆积）
        scheduler.setRemoveOnCancelPolicy(true);
        // 关键优化2：核心线程允许超时退出（空闲60秒），减少资源占用
        scheduler.setKeepAliveTime(60, TimeUnit.SECONDS);
        scheduler.allowCoreThreadTimeOut(true);

        // 关键优化3：定期监控队列长度，超过阈值告警（提前发现任务堆积）
        scheduler.scheduleAtFixedRate(() -> {
            int queueSize = getScheduledQueueSize(scheduler);
            if (queueSize > 500) { // 阈值可根据业务调整
                log.warn("Global scheduler queue size exceeds threshold: {} (max warn: 500)", queueSize);
                // 可选：触发告警（如发送监控通知）
            } else if (queueSize > 0) {
                log.debug("Global scheduler queue size: {}", queueSize);
            }
        }, 1, 1, TimeUnit.MINUTES);

        // 注册JVM关闭钩子：优雅关闭调度器
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down global scheduler...");
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                    log.warn("Scheduler did not terminate in 10s, force shutdown");
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            log.info("Global scheduler shutdown complete");
        }, "scheduler-shutdown-hook"));

        return scheduler;
    }

    /**
     * 反射获取ScheduledThreadPoolExecutor的队列长度（DelayedWorkQueue是私有内部类）
     * 用于监控任务堆积，提前预警
     */
    private static int getScheduledQueueSize(ScheduledThreadPoolExecutor scheduler) {
        try {
            // 获取ScheduledThreadPoolExecutor的queue字段（私有）
            Field queueField = ThreadPoolExecutor.class.getDeclaredField("workQueue");
            queueField.setAccessible(true);
            BlockingQueue<?> queue = (BlockingQueue<?>) queueField.get(scheduler);
            return queue.size();
        } catch (Exception e) {
            log.error("Failed to get scheduler queue size", e);
            return -1; // 监控失败返回-1
        }
    }


    /**
     * 节点连接缓存
     */
    public static Cache<String, QuicNodeWrapper> PEER_CONNECT_CACHE  = Caffeine.newBuilder()
            .maximumSize(10000)
            .expireAfterAccess(NODE_EXPIRATION_TIME, TimeUnit.SECONDS) // 按访问过期，长期不活跃直接淘汰
            .recordStats()
            .removalListener((String nodeId, QuicNodeWrapper node, RemovalCause cause) -> {
                if (node != null) {
                    log.info("Node {} removed from cache (cause: {}), closing resources", nodeId, cause);
                    node.close(); // 缓存淘汰时强制关闭节点资源
                }
            })
            .executor(GLOBAL_SCHEDULER) // 指定listener执行线程池，避免异步线程堆积
            .build();

    /**
     * 请求响应Future缓存：最大容量100万个，30秒过期（请求超时后自动清理，避免内存泄漏）
     * Key：请求ID（UUID），Value：响应Future
     */
    public static Cache<UUID, CompletableFuture<byte[]>> RESPONSE_FUTURECACHE  = Caffeine.newBuilder()
            .maximumSize(1000_000)
            .expireAfterWrite(30, TimeUnit.SECONDS)
            .recordStats()
            .build();

    /**
     * 最多缓存1万个节点的重连计数
     * 重连成功后重置 / 清理计数器
     */
    private final Cache<String, AtomicInteger> RECONNECT_COUNTER = Caffeine.newBuilder()
            .maximumSize(10000)
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .build();



}
