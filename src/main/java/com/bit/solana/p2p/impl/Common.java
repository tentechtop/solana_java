package com.bit.solana.p2p.impl;

import com.github.benmanes.caffeine.cache.RemovalCause;
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

    // 心跳消息
    public static final byte[] HEARTBEAT_SIGNAL = new byte[]{0x00, 0x01};
    // 业务数据消息标识
    public static final byte[] BUSINESS_DATA_SIGNAL = new byte[]{0x00, 0x02};


    /**
     * 全局调度器：固定4个核心线程，轻量处理定时任务（监控、清理、心跳等）
     * DelayedWorkQueue（Java 内置），这个队列本身就是无界的（没有固定容量上限，理论上可无限添加任务，直到 JVM 内存耗尽）；
     */
    public static final ScheduledExecutorService GLOBAL_SCHEDULER = createGlobalScheduler();

    /**
     * 创建全局定时调度器（固定4个核心线程）
     * 保留「任务清理+监控+优雅关闭」等健壮性优化，避免资源泄漏和任务堆积
     */
    private static ScheduledExecutorService createGlobalScheduler() {
        // 核心调整：固定核心线程数为4，不再动态计算
        int corePoolSize = 4;
        log.info("创建全局调度器，固定核心线程数：{}", corePoolSize);

        // 带异常捕获的线程工厂（避免单个任务崩溃导致线程退出）
        ThreadFactory threadFactory = new ThreadFactory() {
            private final AtomicInteger threadNum = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(() -> {
                    try {
                        r.run();
                    } catch (Throwable e) {
                        log.error("全局调度器线程 {} 执行任务时捕获到异常", Thread.currentThread().getName(), e);
                    }
                }, "global-scheduler-" + threadNum.getAndIncrement());
                t.setDaemon(true); // 守护线程，不阻塞应用退出
                // 未捕获异常兜底
                t.setUncaughtExceptionHandler((thread, e) ->
                        log.error("调度器线程 {} 发生未捕获异常", thread.getName(), e));
                return t;
            }
        };

        // 创建ScheduledThreadPoolExecutor（强制使用DelayedWorkQueue，不可替换）
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(
                corePoolSize,
                threadFactory,
                new ThreadPoolExecutor.CallerRunsPolicy() // 极端场景由调用方执行，避免任务丢失
        );

        // 关键优化1：取消的任务立即从队列移除（避免无效任务堆积）
        scheduler.setRemoveOnCancelPolicy(true);
        // 关键优化2：核心线程允许超时退出（空闲60秒），减少资源占用（4个线程空闲时自动释放）
        scheduler.setKeepAliveTime(60, TimeUnit.SECONDS);
        scheduler.allowCoreThreadTimeOut(true);

        // 关键优化3：定期监控队列长度，超过阈值告警（提前发现任务堆积）
        scheduler.scheduleAtFixedRate(() -> {
            int queueSize = getScheduledQueueSize(scheduler);
            if (queueSize > 500) { // 阈值可根据业务调整
                log.warn("全局调度器任务队列长度超过阈值：{}（告警阈值：500）", queueSize);
                // 可选：触发告警（如发送监控通知）
            } else if (queueSize > 0) {
                log.debug("全局调度器任务队列长度：{}", queueSize);
            }
        }, 1, 1, TimeUnit.MINUTES);

        // 注册JVM关闭钩子：优雅关闭调度器
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("开始关闭全局调度器...");
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                    log.warn("调度器未在10秒内完成终止，执行强制关闭");
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            log.info("全局调度器关闭完成");
        }, "scheduler-shutdown-hook"));

        return scheduler;
    }

    /**
     * 反射获取ScheduledThreadPoolExecutor的队列长度（DelayedWorkQueue是私有内部类）
     * 用于监控任务堆积，提前预警
     */
    private static int getScheduledQueueSize(ScheduledThreadPoolExecutor scheduler) {
        try {
            // 获取ThreadPoolExecutor的workQueue私有字段
            Field queueField = ThreadPoolExecutor.class.getDeclaredField("workQueue");
            queueField.setAccessible(true);
            BlockingQueue<?> queue = (BlockingQueue<?>) queueField.get(scheduler);
            return queue.size();
        } catch (Exception e) {
            log.error("获取调度器任务队列长度失败", e);
            return -1; // 监控失败返回-1
        }
    }


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



}
