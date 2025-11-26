package com.bit.solana.p2p.impl;

import com.bit.solana.config.CommonConfig;
import com.bit.solana.p2p.impl.handle.QuicConnHandler;
import com.bit.solana.p2p.impl.handle.QuicStreamHandler;
import com.bit.solana.p2p.peer.Peer;
import com.bit.solana.p2p.peer.RoutingTable;
import com.bit.solana.p2p.protocol.NetworkHandshake;
import com.bit.solana.p2p.protocol.P2PMessage;
import com.bit.solana.p2p.protocol.ProtocolEnum;
import com.bit.solana.util.MultiAddress;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.incubator.codec.quic.*;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.Base58;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

import static com.bit.solana.config.CommonConfig.*;
import static com.bit.solana.p2p.protocol.P2PMessage.newRequestMessage;
import static com.bit.solana.util.ByteUtils.bytesToHex;
import static com.bit.solana.util.ECCWithAESGCM.generateCurve25519KeyPair;
import static com.bit.solana.util.ECCWithAESGCM.generateSharedSecret;

//BIDIRECTIONAL 双向流 所有核心场景（心跳、带响应请求、连接维护）
//UNIDIRECTIONAL 单向流 纯广播 / 日志推送（无需回复）
//        // 单向流创建示例（按需使用）
//        //QuicStreamChannel unidirectionalStream = wrapper.getQuicChannel().createStream(QuicStreamType.UNIDIRECTIONAL, quicStreamHandler).sync().getNow();
@Slf4j
@Component
public class PeerClient {

    @Autowired
    private CommonConfig commonConfig;

    @Autowired
    private RoutingTable routingTable;
    @Autowired
    private QuicConnHandler quicConnHandler;
    @Autowired
    private QuicStreamHandler quicStreamHandler;
    @Autowired
    private PeerServiceImpl peerService;


    // 全局锁（避免重复连接）
    private final ReentrantLock connectLock = new ReentrantLock();


    private NioEventLoopGroup eventLoopGroup;


    private Bootstrap bootstrap;
    private QuicSslContext sslContext;
    private ChannelHandler codec;
    private Channel datagramChannel;

    @PostConstruct
    public void init() throws InterruptedException, ExecutionException {
        eventLoopGroup = new NioEventLoopGroup(Runtime.getRuntime().availableProcessors());
        sslContext = QuicSslContextBuilder.forClient()
                .trustManager(InsecureTrustManagerFactory.INSTANCE)
                .applicationProtocols("solana-p2p")
                .build();
        codec = new QuicClientCodecBuilder()
                .sslContext(sslContext)
                .maxIdleTimeout(CONNECT_KEEP_ALIVE_SECONDS, TimeUnit.SECONDS) // 延长空闲超时，适配持续发送
                .initialMaxData(50 * 1024 * 1024)
                .initialMaxStreamDataBidirectionalLocal(5 * 1024 * 1024)
                .initialMaxStreamDataBidirectionalRemote(5 * 1024 * 1024)
                .initialMaxStreamsBidirectional(MAX_STREAM_COUNT)
                .initialMaxStreamsUnidirectional(MAX_STREAM_COUNT)
                .build();
        bootstrap = new Bootstrap();
        datagramChannel = bootstrap.group(eventLoopGroup)
                .channel(NioDatagramChannel.class)
                .option(ChannelOption.SO_REUSEADDR, true)
                .option(ChannelOption.SO_RCVBUF, 10 * 1024 * 1024)
                .option(ChannelOption.SO_SNDBUF, 10 * 1024 * 1024)
                .handler(codec)
                .bind(0) // 随机绑定可用端口
                .sync()
                .channel();

        log.info("PeerClient 初始化完成，绑定UDP端口:{}", datagramChannel.localAddress());
    }


    /**
     * 基于路由表连接节点
     */
    public QuicNodeWrapper connect(byte[] nodeId) throws ExecutionException, InterruptedException, IOException, TimeoutException {
        String nodeIdStr = bytesToHex(nodeId);
        QuicNodeWrapper existingWrapper = PEER_CONNECT_CACHE.getIfPresent(nodeIdStr);

        if (existingWrapper != null && existingWrapper.isActive()) {
            existingWrapper.setLastSeen(System.currentTimeMillis());
            log.debug("节点{}已处于活跃连接状态，直接返回", nodeId);
            return existingWrapper;
        }
        QuicChannel quicChannel = null;
        QuicStreamChannel heartbeatStream = null;
        Peer node = routingTable.getNode(nodeId);
        if (node == null) {
            return null;
        }
        InetSocketAddress remoteAddress = node.getInetSocketAddress();
        if (remoteAddress == null) {
            return null;
        }
        QuicNodeWrapper quicNodeWrapper = connect(remoteAddress, nodeId);
        quicNodeWrapper.setNodeId(nodeId);
        quicNodeWrapper.setAddress(node.getAddress());
        quicNodeWrapper.setPort(node.getPort());
        PEER_CONNECT_CACHE.put(nodeIdStr, quicNodeWrapper);
        quicNodeWrapper.startHeartbeat(HEARTBEAT_INTERVAL,commonConfig.getSelf().getId());//开启心跳维护这个连接
        return quicNodeWrapper;
    }

    public static void main(String[] args) {
        Cache<byte[], String> cache = Caffeine.newBuilder().build();

        // 第一次：存入 Key（byte[] A）
        byte[] key1 = Base58.decode("abc123"); // 假设返回 byte[] A（地址0x123）
        cache.put(key1, "test");

        // 第二次：查询 Key（byte[] B，内容和A一致，地址0x456）
        byte[] key2 = Base58.decode("abc123"); // 返回 byte[] B（地址0x456）
        String value = cache.getIfPresent(key2);

        System.out.println(key1 == key2); // false（引用不同）
        System.out.println(Arrays.equals(key1, key2)); // true（内容相同）
        System.out.println(value); // null（getIfPresent匹配失败）
    }

    /**
     * 基于自解释型Url连接目标节点
     */
    public QuicNodeWrapper connect(String multiAddressString) throws ExecutionException, InterruptedException, IOException, TimeoutException {
        //不为空
        if (multiAddressString == null || multiAddressString.isEmpty()) {
            return null;
        }
        MultiAddress multiAddress = new MultiAddress(multiAddressString);
        //仅仅支持IPV4 和 QUIC协议
        if (!multiAddress.getIpType().equals("ip4") || !multiAddress.getProtocol().equals(MultiAddress.Protocol.QUIC)) {
            return null;
        }
        byte[] nodeId = Base58.decode(multiAddress.getPeerId());
        String nodeIdStr = bytesToHex(nodeId);
        //从缓存获取连接
        QuicNodeWrapper existingWrapper = PEER_CONNECT_CACHE.asMap().get(nodeIdStr);
        if (existingWrapper != null && existingWrapper.isActive()) {
            existingWrapper.setLastSeen(System.currentTimeMillis());
            log.info("节点{}已处于活跃连接状态，直接返回", multiAddressString);
            return existingWrapper;
        }
        log.info("缓存不存在");
        String ipAddress1 = multiAddress.getIpAddress();
        int port = multiAddress.getPort();
        InetSocketAddress remoteAddress = new InetSocketAddress(ipAddress1, port);
        existingWrapper = connect(remoteAddress, nodeId);

        existingWrapper.setNodeId(nodeId);
        existingWrapper.setAddress(ipAddress1);
        existingWrapper.setPort(port);
        PEER_CONNECT_CACHE.put(nodeIdStr, existingWrapper);
        existingWrapper.startHeartbeat(HEARTBEAT_INTERVAL,commonConfig.getSelf().getId());//开启心跳维护这个连接
        return existingWrapper;
    }

    public QuicNodeWrapper connect(InetSocketAddress remoteAddress,byte[] targetNodeId) throws ExecutionException, InterruptedException, IOException, TimeoutException {
        log.info("开始连接节点:{} {}", remoteAddress.getAddress(), remoteAddress.getPort());
        QuicChannel quicChannel = null;
        QuicStreamChannel heartbeatStream = null;
        quicChannel = QuicChannel.newBootstrap(datagramChannel)
                .handler(quicConnHandler)
                .streamHandler(quicStreamHandler)
                .remoteAddress(remoteAddress)
                .connect()
                .get();
        //创建心跳流维护连接
        heartbeatStream = quicChannel.createStream(QuicStreamType.BIDIRECTIONAL,
                quicStreamHandler).sync().getNow();

        //先用心跳流处理握手 如果不一直就返回空
        //发送握手数据
        NetworkHandshake networkHandshake = new NetworkHandshake();
        networkHandshake.setNodeId(commonConfig.getSelf().getId());
        byte[][] AKeys = generateCurve25519KeyPair();
        byte[] aPrivateKey = AKeys[0];
        byte[] aPublicKey = AKeys[1];
        networkHandshake.setSharedSecret(aPublicKey);

        byte[] serialize = networkHandshake.serialize();
        P2PMessage p2PMessage = newRequestMessage(commonConfig.getSelf().getId(), ProtocolEnum.Network_handshake_V1, serialize);
        byte[] serialize1 = p2PMessage.serialize();

        byte[] requestId = p2PMessage.getRequestId();
        ByteBuf byteBuf = Unpooled.wrappedBuffer(serialize1);
        CompletableFuture<byte[]> responseFuture = new CompletableFuture<>();
        RESPONSE_FUTURECACHE.put(bytesToHex(requestId), responseFuture);
        heartbeatStream.writeAndFlush(byteBuf);
        byte[] bytes = responseFuture.get(5, TimeUnit.SECONDS);//等待返回结果
        if (bytes == null) {
            log.info("节点{}连接失败", remoteAddress);
            return null;
        }
        P2PMessage deserialize = P2PMessage.deserialize(bytes);
        byte[] data = deserialize.getData();
        NetworkHandshake bNetworkHandshake = NetworkHandshake.deserialize(data);
        byte[] bPublicKey = bNetworkHandshake.getSharedSecret();
        //协商
        byte[] sharedSecret = generateSharedSecret(aPrivateKey, bPublicKey);
        log.info("协商成功{}", bytesToHex(sharedSecret));

        Peer ifPresent = ONLINE_PEER_CACHE.getIfPresent(bytesToHex(targetNodeId));
        if (ifPresent != null) {
            ifPresent.setSharedSecret(sharedSecret);
            ONLINE_PEER_CACHE.put(bytesToHex(targetNodeId), ifPresent);
        }else {
            ONLINE_PEER_CACHE.put(bytesToHex(targetNodeId), Peer.builder()
                    .id(targetNodeId)
                    .sharedSecret(sharedSecret)
                    .inetSocketAddress(remoteAddress)
                    .isOnline(true)
                    .lastSeen(System.currentTimeMillis())
                    .build());
        }

        QuicNodeWrapper quicNodeWrapper = new QuicNodeWrapper(GLOBAL_SCHEDULER);
        quicNodeWrapper.setQuicChannel(quicChannel);
        quicNodeWrapper.setHeartbeatStream(heartbeatStream);
        quicNodeWrapper.setLastSeen(System.currentTimeMillis());
        quicNodeWrapper.setOutbound(true);//主动出站
        quicNodeWrapper.setActive(true);
        return quicNodeWrapper;
    }


    //发送任意数据
    public void sendData(byte[] nodeId, byte[] data) throws Exception {
        String nodeIdStr = bytesToHex(nodeId);
        QuicNodeWrapper quicNodeWrapper = null;

        quicNodeWrapper = PEER_CONNECT_CACHE.getIfPresent(nodeIdStr);
        if (quicNodeWrapper == null) {
            quicNodeWrapper = connect(nodeId);
            // 连接创建失败直接抛出异常
            if (quicNodeWrapper == null) {
                throw new ExecutionException(new RuntimeException("节点连接创建失败，nodeId=" +nodeIdStr));
            }
        }
        boolean active = quicNodeWrapper.isActive();
        if (!active) {
            quicNodeWrapper = connect(nodeId);
        }
        sendData(quicNodeWrapper, data);
    }

    public void sendData(QuicNodeWrapper wrapper, byte[] data) throws Exception {
        QuicStreamChannel tempStream = wrapper.createTempStream();
        if (tempStream == null || !tempStream.isActive()) {
            log.error("临时流创建失败或者已经失效，发送数据失败");
            return;
        }
        ByteBuf buf = Unpooled.copiedBuffer(data);
        tempStream.writeAndFlush(buf);
        tempStream.close().sync();
    }


    //发送协议消息
    public QuicNodeWrapper sendData(byte[] nodeId, ProtocolEnum protocol, byte[] request) throws Exception {
        String nodeIdHex = bytesToHex(nodeId);
        log.debug("向节点{}发送协议消息，协议:{}", nodeIdHex, protocol.getProtocol());
        // 1. 获取或创建连接
        QuicNodeWrapper wrapper = PEER_CONNECT_CACHE.getIfPresent(nodeIdHex);
        if (wrapper == null || !wrapper.isActive()) {
            wrapper = connect(nodeId);
            if (wrapper == null) {
                throw new RuntimeException("无法连接到节点，nodeId=" + nodeIdHex);
            }
        }
        P2PMessage p2PMessage = newRequestMessage(commonConfig.getSelf().getId(), protocol, request);
        byte[] serialize = p2PMessage.serialize();
        sendData(wrapper, serialize);
        return wrapper;
    }



    //发送协议数据 根据协议判断有无返回值 有就返回 无就返回null
    public byte[] sendData(byte[] nodeId,ProtocolEnum protocol, byte[] request, long timeout) throws Exception {
        String nodeIdHex = bytesToHex(nodeId);
        log.debug("向节点{}发送协议消息，协议:{}", nodeIdHex, protocol.getProtocol());
        // 1. 获取或创建连接
        QuicNodeWrapper wrapper = PEER_CONNECT_CACHE.getIfPresent(nodeIdHex);

        log.info("全部的数据{}",PEER_CONNECT_CACHE.asMap());
        if (wrapper == null){
            log.info("节点未连接");
        }
        if (wrapper == null || !wrapper.isActive()) {
            wrapper = connect(nodeId);
            if (wrapper == null) {
                throw new RuntimeException("无法连接到节点，nodeId=" + nodeIdHex);
            }
        }
        P2PMessage p2PMessage = newRequestMessage(commonConfig.getSelf().getId(), protocol, request);
        byte[] serialize = p2PMessage.serialize();
        ByteBuf byteBuf = Unpooled.wrappedBuffer(serialize);

        CompletableFuture<byte[]> responseFuture = new CompletableFuture<>();
        RESPONSE_FUTURECACHE.put(bytesToHex(p2PMessage.getRequestId()), responseFuture);

        QuicStreamChannel tempStream = wrapper.createTempStream();
        tempStream.writeAndFlush(byteBuf).addListener(future -> {
            if (!future.isSuccess()) {
                log.error("节点{}心跳消息发送失败", bytesToHex(nodeId), future.cause());
                // 移除缓存的future，避免内存泄漏
                RESPONSE_FUTURECACHE.asMap().remove(bytesToHex(p2PMessage.getRequestId()));
            }
        });
        return responseFuture.get(timeout, TimeUnit.SECONDS);
    }



    public void disconnect(byte[] nodeId) {


    }

    /**
     * 重连
     * @param nodeId
     */
    private void reconnect(byte[] nodeId) {


    }


    @PreDestroy
    public void shutdown() throws InterruptedException {
        if (datagramChannel != null) {
            datagramChannel.close().syncUninterruptibly();
        }
        if (eventLoopGroup != null) {
            eventLoopGroup.shutdownGracefully();
        }
    }
}
