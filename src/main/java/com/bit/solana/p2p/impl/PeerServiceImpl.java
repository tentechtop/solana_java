package com.bit.solana.p2p.impl;

import com.bit.solana.database.DataBase;
import com.bit.solana.database.DbConfig;
import com.bit.solana.database.rocksDb.TableEnum;
import com.bit.solana.p2p.peer.Peer;
import com.bit.solana.p2p.PeerService;
import com.bit.solana.p2p.peer.RoutingTable;
import com.bit.solana.p2p.peer.Settings;
import com.bit.solana.p2p.protocol.ProtocolEnum;
import com.bit.solana.p2p.protocol.ProtocolRegistry;
import com.bit.solana.p2p.protocol.ReturnProtocolHandler;
import com.bit.solana.p2p.protocol.VoidProtocolHandler;
import com.bit.solana.structure.block.BlockHeader;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.incubator.codec.quic.*;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.cert.CertificateException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static com.bit.solana.util.ByteUtils.bytesToHex;
import static com.bit.solana.util.ECCWithAESGCM.generateCurve25519KeyPair;
import static java.lang.Thread.sleep;

@Slf4j
@Component
@Data
public class PeerServiceImpl implements PeerService {
    // 消息过期时间
    public static final long MESSAGE_EXPIRATION_TIME = 30;//秒
    // 节点过期时间
    public static final long NODE_EXPIRATION_TIME = 60;//秒
    //节点心跳 15秒
    public static final long HEARTBEAT_INTERVAL = 15;

    public static final byte[] PEER_KEY = "PEER".getBytes();

    //节点信息
    private Peer self;//本地节点信息
    // QUIC服务器通道
    private Channel quicServerChannel;
    // 事件循环组
    private NioEventLoopGroup eventLoopGroup;
    // 节点ID -> 节点连接封装（线程安全）
    public static Map<String, QuicNodeWrapper> peerNodeMap = new ConcurrentHashMap<>();
    // 节点地址 -> 节点ID（反向映射，用于快速查询）
    public static Map<InetSocketAddress, String> addrToNodeIdMap = new ConcurrentHashMap<>();
    public Cache<String, Long> messageCache;



    @Autowired
    private Settings settings;
    @Autowired
    private DbConfig config;
    @Autowired
    private RoutingTable routingTable;
    @Autowired
    private ProtocolRegistry protocolRegistry;
    @Autowired
    private ServiceQuicStreamHandler quicStreamHandler;

    /**
     * 初始化节点信息
     */
    @PostConstruct
    @Override
    public void init() throws IOException, CertificateException {
        log.info("init初始化节点信息");
        DataBase dataBase = config.getDataBase();

        //保存到本地数据库  下次从本地数据库获取
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


        String selfNodeId = bytesToHex(self.getId());
        log.info("本地节点初始化完成，ID: {}, 监听端口: {}", selfNodeId, self.getPort());

        messageCache = Caffeine.newBuilder()
                .maximumSize(10000)  // 最大缓存
                .expireAfterWrite(10, TimeUnit.MINUTES)  // 10分钟过期
                .build();// 缓存未命中时从数据源加载


        // 启动QUIC服务器
        startQuicServer();
        // 启动节点过期清理任务（定时移除无效节点）
        startNodeCleanupTask();


        // 1. 注册有返回值的区块查询处理器
        protocolRegistry.register(ProtocolEnum.BLOCK_V1, (ReturnProtocolHandler) requestParams -> {
            // 此处替换为实际的protobuf反序列化+业务逻辑
            log.info("处理区块查询请求，参数：{}", new String(requestParams));
            // 模拟返回区块数据（protobuf序列化后的二进制）
            return "区块数据-123456".getBytes();
        });

        // 2. 注册无返回值的交易提交处理器
        protocolRegistry.register(ProtocolEnum.TX_V1, (VoidProtocolHandler) requestParams -> {
            // 此处替换为实际的protobuf反序列化+业务逻辑
            log.info("处理交易提交请求，参数：{}", new String(requestParams));
            // 无返回值，执行业务逻辑即可
        });

        protocolRegistry.register(ProtocolEnum.CHAIN_V1, (ReturnProtocolHandler) requestParams -> {
            log.info("处理链查询请求，参数");
            BlockHeader deserialize = BlockHeader.deserialize(requestParams);
            deserialize.setSlot(123);
            return deserialize.serialize();
        });
    }

    /**
     * 启动QUIC服务器（优化SSL配置、参数调优）
     */
    public void startQuicServer() throws CertificateException {
        eventLoopGroup = new NioEventLoopGroup(Runtime.getRuntime().availableProcessors());

        try {
            // 生产环境建议替换为CA签名证书，此处保留自签名用于开发
            SelfSignedCertificate selfSignedCert = new SelfSignedCertificate();

            // 构建QUIC SSL上下文（与PeerClient保持协议一致）
            QuicSslContext sslContext = QuicSslContextBuilder.forServer(
                            selfSignedCert.privateKey(), null, selfSignedCert.certificate())
                    .applicationProtocols("solana-p2p")
                    .build();

            // 构建QUIC服务器编解码器（参数与客户端对齐，避免兼容性问题）
            ChannelHandler quicCodec = new QuicServerCodecBuilder()
                    .sslContext(sslContext)
                    .maxIdleTimeout(300, TimeUnit.SECONDS)
                    .initialMaxData(50 * 1024 * 1024) // 与客户端一致（50MB）
                    .initialMaxStreamDataBidirectionalLocal(5 * 1024 * 1024) // 与客户端一致（5MB）
                    .initialMaxStreamDataBidirectionalRemote(5 * 1024 * 1024)
                    .initialMaxStreamsBidirectional(200) // 与客户端一致
                    .tokenHandler(InsecureQuicTokenHandler.INSTANCE) // 生产环境需自定义安全token处理器
                    .handler(quicStreamHandler)
                    .streamHandler(new ChannelInitializer<QuicStreamChannel>() {
                        @Override
                        protected void initChannel(QuicStreamChannel ch) throws Exception {
                            // 原QuicStreamInitializer的核心逻辑：向pipeline添加QuicStreamHandler
                            ch.pipeline().addLast(quicStreamHandler);
                        }
                    })
                    .build();

            // 绑定端口启动服务器
            Bootstrap bootstrap = new Bootstrap();
            quicServerChannel = bootstrap.group(eventLoopGroup)
                    .channel(NioDatagramChannel.class)
                    .option(ChannelOption.SO_REUSEADDR, true)
                    .option(ChannelOption.SO_RCVBUF, 10 * 1024 * 1024)
                    .option(ChannelOption.SO_SNDBUF, 10 * 1024 * 1024)
                    .handler(quicCodec)
                    .bind(self.getPort())
                    .sync()
                    .channel();

            log.info("QUIC服务器启动成功，监听端口: {}", self.getPort());

        } catch (Exception e) {
            log.error("启动QUIC服务器失败", e);
            throw new RuntimeException("QUIC服务器启动失败", e);
        }
    }




    /**
     * 关闭服务器
     */
    public void shutdown() throws InterruptedException {
        if (quicServerChannel != null) {
            quicServerChannel.closeFuture().sync();
        }
        if (eventLoopGroup != null) {
            eventLoopGroup.shutdownGracefully();
        }
        log.info("QUIC服务器已关闭");
    }




    /**
     * 启动节点过期清理任务（每30秒执行一次）
     */
    private void startNodeCleanupTask() {
        eventLoopGroup.scheduleAtFixedRate(() -> {
            long currentTime = System.currentTimeMillis();
            peerNodeMap.entrySet().removeIf(entry -> {
                QuicNodeWrapper wrapper = entry.getValue();
                boolean expired = currentTime - wrapper.getLastActiveTime() > NODE_EXPIRATION_TIME * 1000;
                if (expired || !wrapper.isActive()) {
                    String nodeId = entry.getKey();
                    InetSocketAddress addr = wrapper.getInetSocketAddress();
                    log.info("节点{}({})已过期或断开，从节点列表移除", nodeId, addr);
                    addrToNodeIdMap.remove(addr);
                    //TODO 路由表


                    wrapper.close();
                    return true;
                }
                return false;
            });
        }, 0, 30, TimeUnit.SECONDS);
    }
}
