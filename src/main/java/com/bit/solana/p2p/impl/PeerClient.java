package com.bit.solana.p2p.impl;

import com.bit.solana.p2p.peer.Peer;
import com.bit.solana.p2p.peer.RoutingTable;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.ChannelInputShutdownReadComplete;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.incubator.codec.quic.*;
import io.netty.util.NetUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import static com.bit.solana.p2p.impl.PeerServiceImpl.*;
import static com.bit.solana.p2p.protocol.P2pMessageHeader.HEARTBEAT_SIGNAL;
import static com.bit.solana.util.ByteUtils.bytesToHex;
import static java.lang.Thread.sleep;


@Slf4j
@Component
public class PeerClient {
    // 配置参数（可移至配置文件）
    private static final long SEND_INTERVAL = 15; // 心跳间隔（秒）
    private static final long RECONNECT_DELAY = 3; // 重连延迟（秒）
    private static final int MAX_RECONNECT_ATTEMPTS = 10; // 最大重连次数
    private static final int DEFAULT_TIMEOUT = 5000; // 默认请求超时（毫秒）
    public static final long NODE_EXPIRATION_TIME = 60;//秒 节点过期时间
    private static final int MAX_FRAME_SIZE = 1024 * 1024; // 最大帧大小 1MB
    private static final int EVENT_LOOP_THREADS = Math.max(2, Runtime.getRuntime().availableProcessors() * 2); // EventLoop 线程数


    @Autowired
    private RoutingTable routingTable;

    // 全局锁（避免重复连接）
    private final ReentrantLock connectLock = new ReentrantLock();
    // 节点ID -> 重连计数器（控制最大重连次数）
    private final Map<String, AtomicInteger> reconnectCounter = new ConcurrentHashMap<>();
    // 节点ID -> 响应Future缓存（用于同步等待响应）
    private final Map<String, CompletableFuture<byte[]>> responseFutureMap = new ConcurrentHashMap<>();
    // 节点ID -> 心跳任务调度器（用于取消心跳）
    private final Map<String, ScheduledFuture<?>> heartbeatTaskMap = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "peer-client-scheduler");
        t.setDaemon(true); // 守护线程，避免阻塞应用退出
        return t;
    }); // 定时任务线程池

    private NioEventLoopGroup eventLoopGroup;
    private Bootstrap bootstrap;
    private QuicSslContext sslContext;
    private ChannelHandler codec;
    private Channel datagramChannel;

    @PostConstruct
    public void init() throws InterruptedException, ExecutionException {
        // 初始化EventLoopGroup（核心线程池，生产环境建议配置为CPU核心数）
        eventLoopGroup = new NioEventLoopGroup(Runtime.getRuntime().availableProcessors() * 2);
        sslContext = QuicSslContextBuilder.forClient()
                .trustManager(InsecureTrustManagerFactory.INSTANCE)
                .applicationProtocols("solana-p2p")
                .build();
        codec = new QuicClientCodecBuilder()
                .sslContext(sslContext)
                .maxIdleTimeout(300, TimeUnit.SECONDS) // 延长空闲超时，适配持续发送
                .initialMaxData(10 * 1024 * 1024) // 增大数据限制
                .initialMaxStreamDataBidirectionalLocal(10 * 1024 * 1024)
                .build();
        bootstrap = new Bootstrap();

/*        datagramChannel = bootstrap.group(eventLoopGroup)
                .channel(NioDatagramChannel.class)
                .handler(codec)
                .bind(0).sync().channel();*/

        datagramChannel = bootstrap.group(eventLoopGroup)
                .channel(NioDatagramChannel.class)
                .option(ChannelOption.SO_REUSEADDR, true) // 复用端口
                .option(ChannelOption.SO_RCVBUF, 1024 * 1024) // 接收缓冲区 1MB
                .option(ChannelOption.SO_SNDBUF, 1024 * 1024) // 发送缓冲区 1MB
                .handler(codec)
                .bind(0) // 随机绑定可用端口
                .sync()
                .channel();


        log.info("PeerClient 初始化完成，绑定UDP端口:{}", datagramChannel.localAddress());

        //连接自己
        InetSocketAddress inetSocketAddress = new InetSocketAddress("127.0.0.1", 8333);

        new Thread(() -> {
            try {
                sleep(1000);
                QuicStreamChannel connect = connect(inetSocketAddress);
            } catch (Exception e) {
                log.error("连接自己异常:{}", e.getMessage());
            }
        }).start();

    }


    /**
     * 主动连接某个节点（幂等）
     */
    public QuicNodeWrapper connect(String nodeId) throws ExecutionException, InterruptedException {
        // 1. 幂等检查：已连接且活跃则直接返回
        QuicNodeWrapper existingWrapper = peerNodeMap.get(nodeId);
        if (existingWrapper != null && existingWrapper.isActive()) {
            existingWrapper.setLastActiveTime(System.currentTimeMillis());
            log.debug("节点{}已处于活跃连接状态，直接返回", nodeId);
            return existingWrapper;
        }
        // 2. 加锁避免并发连接
        connectLock.lock();

        try {
            // 二次检查（防止锁等待期间已建立连接）
            existingWrapper = peerNodeMap.get(nodeId);
            if (existingWrapper != null && existingWrapper.isActive()) {
                existingWrapper.setLastActiveTime(System.currentTimeMillis());
                return existingWrapper;
            }

            Peer node = routingTable.getNode(nodeId);
            if (node == null) {
                throw new IllegalArgumentException("节点ID不存在: " + nodeId);
            }
            InetSocketAddress remoteAddress = node.getInetSocketAddress();
            if (remoteAddress == null) {
                throw new IllegalArgumentException("节点地址无效: " + nodeId);
            }

            InetSocketAddress inetSocketAddress = node.getInetSocketAddress();
            QuicChannel quicChannel = QuicChannel.newBootstrap(datagramChannel)
                    .streamHandler(new QuicStreamHandler())
                    .remoteAddress(inetSocketAddress)
                    .connect()
                    .get();
            QuicStreamChannel streamChannel = quicChannel.createStream(QuicStreamType.BIDIRECTIONAL,
                    new QuicStreamHandler()).sync().getNow();

            QuicNodeWrapper quicNodeWrapper = new QuicNodeWrapper();
            quicNodeWrapper.setNodeId(nodeId);
            quicNodeWrapper.setQuicChannel(quicChannel);
            quicNodeWrapper.setStreamChannel(streamChannel);
            quicNodeWrapper.setAddress(node.getAddress());
            quicNodeWrapper.setPort(node.getPort());
            quicNodeWrapper.setInetSocketAddress(inetSocketAddress);
            quicNodeWrapper.setOutbound(true);//主动出站
            quicNodeWrapper.setActive(true);
            quicNodeWrapper.setLastActiveTime(System.currentTimeMillis());
            peerNodeMap.put(nodeId, quicNodeWrapper);
            addrToNodeIdMap.put(inetSocketAddress, nodeId);

            startHeartbeatTask(nodeId);
            return quicNodeWrapper;
        }finally {
            connectLock.unlock();
        }
    }


    public QuicStreamChannel connect(InetSocketAddress address) throws InterruptedException, ExecutionException {
        QuicChannel quicChannel = QuicChannel.newBootstrap(datagramChannel)
                .streamHandler(new QuicStreamHandler())
                .remoteAddress(address)
                .connect()
                .get();

        QuicStreamChannel streamChannel = quicChannel.createStream(QuicStreamType.BIDIRECTIONAL,
                new QuicStreamHandler()).sync().getNow();

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> {
            if (streamChannel.isActive()) {
                // 生成随机二进制测试数据（也可替换为自定义二进制内容）
                byte[] sendData = HEARTBEAT_SIGNAL;
                // 封装为ByteBuf发送
                ByteBuf sendBuf = Unpooled.copiedBuffer(sendData);
                streamChannel.writeAndFlush(sendBuf)
                        .addListener(future -> {
                            if (future.isSuccess()) {
                                System.out.printf("发送成功：长度=%d字节，内容=%s%n",
                                        sendData.length, bytesToHex(sendData));
                            } else {
                                System.err.println("发送失败：" + future.cause().getMessage());
                            }
                        });
            } else {
                System.err.println("流已断开，停止发送");
                scheduler.shutdown();
            }
        }, 0, SEND_INTERVAL, TimeUnit.SECONDS);

        return streamChannel;
    }


    private static final Random RANDOM = new Random();
    private static byte[] generateRandomBinaryData() {
        byte[] data = new byte[16];
        RANDOM.nextBytes(data);
        return data;
    }


    /** 无返回值发送 */
    public void sendData(String nodeId, byte[] data) {
        Objects.requireNonNull(nodeId, "节点ID不能为空");
        Objects.requireNonNull(data, "发送数据不能为空");
        // 1. 获取连接对象
        QuicNodeWrapper wrapper = peerNodeMap.get(nodeId);
        if (wrapper == null || !wrapper.isActive()) {
            log.error("节点{}连接不存在或已失效，尝试重连", nodeId);
            try {
                wrapper = connect(nodeId); // 自动重连
            } catch (Exception e) {
                log.error("节点{}重连失败，发送数据失败", nodeId, e);
                return;
            }
        }
        // 2. 发送数据
        sendDataToStream(wrapper.getStreamChannel(), data, nodeId);
    }

    /** 按地址发送*/
    public void sendData(InetSocketAddress nodeAddress, byte[] data) {
        Objects.requireNonNull(nodeAddress, "节点地址不能为空");
        Objects.requireNonNull(data, "发送数据不能为空");
        // 1. 通过地址获取节点ID
        String nodeId = addrToNodeIdMap.get(nodeAddress);
        if (nodeId != null) {
            sendData(nodeId, data);
            return;
        }
        // 2. 临时连接发送
        try {
            QuicStreamChannel streamChannel = connect(nodeAddress);
            sendDataToStream(streamChannel, data, nodeAddress.toString());
        } catch (Exception e) {
            log.error("向临时节点{}发送数据失败", nodeAddress, e);
        }
    }

    /** 有返回值的同步发送 */
    public byte[] sendData(String nodeId, byte[] request, long timeout) throws Exception {
        Objects.requireNonNull(nodeId, "节点ID不能为空");
        Objects.requireNonNull(request, "请求数据不能为空");

        // 创建响应Future
        CompletableFuture<byte[]> responseFuture = new CompletableFuture<>();
        String requestId = UUID.randomUUID().toString();
        responseFutureMap.put(requestId, responseFuture);
        try {
            // 获取/创建连接
            QuicNodeWrapper wrapper = connect(nodeId);
            // 发送数据
            sendDataToStream(wrapper.getStreamChannel(), request, nodeId);
            // 等待响应
            return responseFuture.get(timeout, TimeUnit.MILLISECONDS);
        } finally {
            // 清理Future
            responseFutureMap.remove(requestId);
        }
    }

    /**
     * 按地址同步发送数据（等待响应）
     *
     * @param nodeAddress 节点地址
     * @param request     请求数据
     * @param timeout     超时时间（毫秒）
     * @return 响应数据
     * @throws Exception 发送/接收异常
     */
    public byte[] sendData(InetSocketAddress nodeAddress, byte[] request, long timeout) throws Exception {
        Objects.requireNonNull(nodeAddress, "节点地址不能为空");
        String nodeId = addrToNodeIdMap.get(nodeAddress);
        if (nodeId != null) {
            return sendData(nodeId, request, timeout);
        }

        // 临时连接同步发送
        CompletableFuture<byte[]> responseFuture = new CompletableFuture<>();
        QuicStreamChannel streamChannel = connect(nodeAddress);

        // 注册响应处理器
        streamChannel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                if (msg instanceof ByteBuf buf) {
                    byte[] data = new byte[buf.readableBytes()];
                    buf.readBytes(data);
                    responseFuture.complete(data);
                }
                ctx.close();
            }

            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                responseFuture.completeExceptionally(cause);
                ctx.close();
            }
        });

        // 发送数据
        ByteBuf buf = Unpooled.copiedBuffer(request);
        streamChannel.writeAndFlush(buf).sync();

        // 等待响应
        return responseFuture.get(timeout, TimeUnit.MILLISECONDS);
    }

    /**
     * 断开指定节点连接
     * @param nodeId 节点ID
     */
    public void disconnect(String nodeId) {
        connectLock.lock();
        try {
            // 1. 取消心跳任务
            ScheduledFuture<?> task = heartbeatTaskMap.remove(nodeId);
            if (task != null) {
                task.cancel(false);
            }

            // 2. 关闭连接资源
            QuicNodeWrapper wrapper = peerNodeMap.remove(nodeId);
            if (wrapper != null) {
                closeQuicResources(wrapper);
                addrToNodeIdMap.remove(wrapper.getInetSocketAddress());
                log.info("节点{}连接已断开", nodeId);
            }

            // 3. 清理响应Future
            responseFutureMap.entrySet().removeIf(entry -> {
                if (entry.getValue().isDone()) {
                    return false;
                }
                entry.getValue().completeExceptionally(new IOException("节点已断开连接: " + nodeId));
                return true;
            });

            // 4. 重置重连计数器
            reconnectCounter.remove(nodeId);
        } finally {
            connectLock.unlock();
        }
    }


    /**
     * 启动心跳任务（维持QUIC连接）
     * @param nodeId 节点ID
     */
    private void startHeartbeatTask(String nodeId) {
        // 取消原有心跳任务
        ScheduledFuture<?> oldTask = heartbeatTaskMap.remove(nodeId);
        if (oldTask != null) {
            oldTask.cancel(false);
        }

        // 创建新心跳任务
        ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(() -> {
            try {
                QuicNodeWrapper wrapper = peerNodeMap.get(nodeId);
                if (wrapper == null || !wrapper.isActive()) {
                    log.warn("节点{}连接已失效，触发重连", nodeId);
                    reconnect(nodeId);
                    return;
                }
                sendDataToStream(wrapper.getStreamChannel(), HEARTBEAT_SIGNAL, nodeId);
                // 更新最后活跃时间
                wrapper.setLastActiveTime(System.currentTimeMillis());
                log.debug("节点{}心跳发送成功", nodeId);
            } catch (Exception e) {
                log.error("节点{}心跳发送失败", nodeId, e);
                reconnect(nodeId);
            }
        }, SEND_INTERVAL, SEND_INTERVAL, TimeUnit.SECONDS);

        heartbeatTaskMap.put(nodeId, task);
    }




    /**
     * 断线重连
     *
     * @param nodeId 节点ID
     */
    private void reconnect(String nodeId) {
        // 1. 检查重连次数
        AtomicInteger counter = reconnectCounter.computeIfAbsent(nodeId, k -> new AtomicInteger(0));
        int currentAttempt = counter.incrementAndGet();
        if (currentAttempt > MAX_RECONNECT_ATTEMPTS) {
            log.error("节点{}重连次数达到上限({})，停止重连", nodeId, MAX_RECONNECT_ATTEMPTS);
            disconnect(nodeId);
            return;
        }

        // 2. 延迟重连
        scheduler.schedule(() -> {
            try {
                log.info("节点{}开始第{}次重连", nodeId, currentAttempt);
                connect(nodeId);
                log.info("节点{}第{}次重连成功", nodeId, currentAttempt);
            } catch (Exception e) {
                log.error("节点{}第{}次重连失败", nodeId, currentAttempt, e);
                reconnect(nodeId); // 递归重连
            }
        }, RECONNECT_DELAY * currentAttempt, TimeUnit.SECONDS); // 指数退避
    }




    @PreDestroy
    public void shutdown() {
        log.info("开始关闭 PeerClient 资源");

        // 1. 取消所有心跳任务
        heartbeatTaskMap.values().forEach(task -> {
            task.cancel(false);
        });
        heartbeatTaskMap.clear();

        // 2. 关闭定时任务线程池
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
        }

        // 3. 关闭所有 QUIC 连接
        peerNodeMap.values().forEach(wrapper -> {
            try {
                closeQuicResources(wrapper);
            } catch (Exception e) {
                log.error("关闭节点{}连接失败", wrapper.getNodeId(), e);
            }
        });
        peerNodeMap.clear();
        addrToNodeIdMap.clear();

        // 4. 关闭 Netty 资源
        if (datagramChannel != null) {
            datagramChannel.close().syncUninterruptibly();
        }
        if (eventLoopGroup != null) {
            eventLoopGroup.shutdownGracefully(1, 5, TimeUnit.SECONDS);
        }

        // 5. 清空缓存
        responseFutureMap.clear();
        reconnectCounter.clear();

        log.info("PeerClient 资源关闭完成");
    }

    /**
     * 关闭 QUIC 资源
     */
    private void closeQuicResources(QuicNodeWrapper wrapper) {
        if (wrapper.getStreamChannel() != null) {
            wrapper.getStreamChannel().close().syncUninterruptibly();
        }
        if (wrapper.getQuicChannel() != null) {
            closeQuicChannel(wrapper.getQuicChannel());
        }
        wrapper.setActive(false);
    }

    /**
     * 关闭 QUIC Channel
     */
    private void closeQuicChannel(QuicChannel quicChannel) {
        if (quicChannel.isActive()) {
            quicChannel.close().addListener(future -> {
                if (!future.isSuccess()) {
                    log.error("关闭QUIC Channel失败", future.cause());
                }
            });
        }
    }

    /**
     * 发送数据到 QUIC 流
     */
    private void sendDataToStream(QuicStreamChannel streamChannel, byte[] data, String nodeId) {
        if (streamChannel == null || !streamChannel.isActive()) {
            log.error("节点{}流通道未激活，发送失败", nodeId);
            return;
        }

        ByteBuf buf = Unpooled.copiedBuffer(data);
        streamChannel.writeAndFlush(buf)
                .addListener((GenericFutureListener<Future<Void>>) future -> {
                    if (future.isSuccess()) {
                        log.debug("节点{}发送数据成功，长度:{}字节", nodeId, data.length);
                    } else {
                        log.error("节点{}发送数据失败", nodeId, future.cause());
                        // 标记连接失效
                        QuicNodeWrapper wrapper = peerNodeMap.get(nodeId);
                        if (wrapper != null) {
                            wrapper.setActive(false);
                        }
                    }
                });
    }


}