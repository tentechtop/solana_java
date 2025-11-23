package com.bit.solana.p2p.impl;

import com.bit.solana.database.DataBase;
import com.bit.solana.database.DbConfig;
import com.bit.solana.database.rocksDb.TableEnum;
import com.bit.solana.p2p.peer.Peer;
import com.github.benmanes.caffeine.cache.RemovalCause;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.bitcoinj.core.Base58;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import static com.bit.solana.util.ECCWithAESGCM.generateCurve25519KeyPair;


@Slf4j
@Component
public class CommonConfig {
    //节点信息
    @Getter
    private Peer self;//本地节点信息
    @Autowired
    private DbConfig config;


    @PostConstruct
    public void init() throws Exception {
        DataBase dataBase = config.getDataBase();
        byte[] bytes = dataBase.get(TableEnum.PEER, PEER_KEY);
        if (bytes == null) {
            self = new Peer();
            self.setAddress("127.0.0.1");
            self.setPort(8333);
            byte[][] aliceKeys = generateCurve25519KeyPair();
            byte[] alicePrivateKey = aliceKeys[0];
            byte[] alicePublicKey = aliceKeys[1];
            self.setId(alicePublicKey);
            self.setPrivateKey(alicePrivateKey);

            //保存到本地数据库
            byte[] serialize = self.serialize();
            dataBase.insert(TableEnum.PEER, PEER_KEY, serialize);
        } else {
            //反序列化
            self = Peer.deserialize(bytes);
        }
        byte[] selfNodeId = self.getId();
        log.info("本地节点初始化完成，ID: {}, 监听端口: {}", Base58.encode(selfNodeId), self.getPort());
        log.info("Base58.encode(selfNodeId){}",Base58.encode(selfNodeId).length());
    }


    //连接保活时间300秒
    public static final long CONNECT_KEEP_ALIVE_SECONDS = 300;
    //临时流超时时间
    public static final long TEMP_STREAM_TIMEOUT_SECONDS = 30;
    // 消息过期时间
    public static final long MESSAGE_EXPIRATION_TIME = 30;//秒
    // 节点过期时间
    public static final long NODE_EXPIRATION_TIME = 60;//秒
    //节点心跳 15秒
    public static final long HEARTBEAT_INTERVAL = 15;
    // 最大重连次数
    public static final int MAX_RECONNECT_ATTEMPTS = 10;
    // 默认请求超时（毫秒）
    public static final int DEFAULT_TIMEOUT = 5000;
    // 本地节点标识
    public static final byte[] PEER_KEY = "LOCAL_PEER".getBytes();

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
     * 节点连接缓存 节点ID -> 节点的连接
     */
    public static Cache<byte[], QuicNodeWrapper> PEER_CONNECT_CACHE  = Caffeine.newBuilder()
            .maximumSize(10000)
            .expireAfterAccess(NODE_EXPIRATION_TIME, TimeUnit.SECONDS) //按访问过期，长期不活跃直接淘汰
            .recordStats()
            // 改为同步执行（避免调度器延迟），或限制监听器执行超时
            .removalListener((byte[] nodeId, QuicNodeWrapper node, RemovalCause cause) -> {
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
     * Key：请求ID，Value：响应Future
     * 16字节的UUIDV7 - > CompletableFuture<byte[]>
     */
    public static Cache<byte[], CompletableFuture<byte[]>> RESPONSE_FUTURECACHE  = Caffeine.newBuilder()
            .maximumSize(1000_000)
            .expireAfterWrite(30, TimeUnit.SECONDS)
            .weakValues() // 弱引用存储Future，GC时可回收
            .recordStats()
            .build();

    /**
     * 最多缓存1万个节点的重连计数
     * 重连成功后重置 / 清理计数器
     * 节点ID - >节点的重连计数
     */
    public final Cache<byte[], AtomicInteger> RECONNECT_COUNTER = Caffeine.newBuilder()
            .maximumSize(10000)
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .build();


    /**
     * UUIDV7 16字节ID - > 缓存时间 代表消息已经处理
     */
    public Cache<byte[], Long> MESSAGE_CACHE = Caffeine.newBuilder()
            .maximumSize(10000)  // 最大缓存
            .expireAfterWrite(10, TimeUnit.MINUTES)  // 10分钟过期
            .build();// 缓存未命中时从数据源加载



    @PreDestroy
    public void shutdown() throws InterruptedException {
        GLOBAL_SCHEDULER.shutdown();
        log.info("关闭全局调度器");

        //关闭所有的连接
        for (QuicNodeWrapper node : PEER_CONNECT_CACHE.asMap().values()) {
            node.close();
        }
        //清空所有的缓存
        PEER_CONNECT_CACHE.invalidateAll();
        RESPONSE_FUTURECACHE.invalidateAll();
        RECONNECT_COUNTER.invalidateAll();
        MESSAGE_CACHE.invalidateAll();
    }


}
