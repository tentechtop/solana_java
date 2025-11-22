package com.bit.solana.p2p.impl;

import com.bit.solana.database.DataBase;
import com.bit.solana.database.DbConfig;
import com.bit.solana.database.rocksDb.TableEnum;
import com.bit.solana.p2p.PeerService;
import com.bit.solana.p2p.impl.handle.QuicConnHandler;
import com.bit.solana.p2p.impl.handle.QuicStreamHandler;
import com.bit.solana.p2p.peer.Peer;
import com.bit.solana.p2p.peer.RoutingTable;
import com.bit.solana.p2p.peer.Settings;
import com.bit.solana.p2p.protocol.ProtocolRegistry;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.incubator.codec.quic.*;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.Base58;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.security.cert.CertificateException;
import java.util.concurrent.TimeUnit;

import static com.bit.solana.p2p.impl.Common.CONNECT_KEEP_ALIVE_SECONDS;
import static com.bit.solana.p2p.impl.Common.PEER_KEY;
import static com.bit.solana.util.ECCWithAESGCM.generateCurve25519KeyPair;

@Slf4j
@Data
@Component
public class PeerServiceImpl implements PeerService {
    @Autowired
    private Settings settings;
    @Autowired
    private DbConfig config;
    @Autowired
    private RoutingTable routingTable;
    @Autowired
    private ProtocolRegistry protocolRegistry;
    @Autowired
    private QuicConnHandler quicConnHandler;
    @Autowired
    private QuicStreamHandler quicStreamHandler;

    //节点信息
    private Peer self;//本地节点信息
    // QUIC服务器通道
    private Channel quicServerChannel;
    // 事件循环组
    private NioEventLoopGroup eventLoopGroup;
    //QUIC SSL上下文
    private QuicSslContext sslContext;
    private SelfSignedCertificate selfSignedCert;


    @PostConstruct
    @Override
    public void init() throws IOException, CertificateException {
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
        // 启动QUIC服务器
        startQuicServer();
    }


    public void startQuicServer() throws CertificateException {
        eventLoopGroup = new NioEventLoopGroup(Runtime.getRuntime().availableProcessors());
        try {
            // 生产环境建议替换为CA签名证书，此处保留自签名用于开发
            selfSignedCert = new SelfSignedCertificate();
            // 构建QUIC SSL上下文（与PeerClient保持协议一致）
            sslContext = QuicSslContextBuilder.forServer(
                            selfSignedCert.privateKey(), null, selfSignedCert.certificate())
                    .applicationProtocols("solana-p2p")
                    .build();
            ChannelHandler quicCodec = new QuicServerCodecBuilder()
                    .sslContext(sslContext)
                    .maxIdleTimeout(CONNECT_KEEP_ALIVE_SECONDS, TimeUnit.SECONDS)
                    .initialMaxData(50 * 1024 * 1024)
                    .initialMaxStreamDataBidirectionalLocal(5 * 1024 * 1024)
                    .initialMaxStreamDataBidirectionalRemote(5 * 1024 * 1024)
                    .initialMaxStreamsBidirectional(200)
                    .tokenHandler(InsecureQuicTokenHandler.INSTANCE) // 生产环境需自定义安全token处理器
                    .handler(quicConnHandler)//代表一个 QUIC 连接（连接级），管理连接的建立 / 关闭、全局流量控制、握手等
                    .streamHandler(new ChannelInitializer<QuicStreamChannel>() {
                        @Override
                        protected void initChannel(QuicStreamChannel ch) throws Exception {
                            //向pipeline添加QuicStreamHandler
                            ch.pipeline().addLast(quicStreamHandler);//代表 QUIC 连接内的一个流（流级），每个连接可以创建多个流，流负责具体的数据读写。
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
        }catch (Exception e) {
            log.error("启动QUIC服务器失败", e);
            throw new RuntimeException("QUIC服务器启动失败", e);
        }
    }


    /**
     * 关闭服务器
     */
    @PreDestroy
    public void shutdown() throws InterruptedException {
        if (quicServerChannel != null) {
            quicServerChannel.closeFuture().sync();
        }
        if (eventLoopGroup != null) {
            eventLoopGroup.shutdownGracefully();
        }
        log.info("QUIC服务器已关闭");
    }
}
