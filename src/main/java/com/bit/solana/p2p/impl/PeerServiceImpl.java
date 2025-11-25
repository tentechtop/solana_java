package com.bit.solana.p2p.impl;

import com.bit.solana.config.CommonConfig;
import com.bit.solana.config.SystemConfig;
import com.bit.solana.p2p.PeerService;
import com.bit.solana.p2p.impl.handle.QuicConnHandler;
import com.bit.solana.p2p.impl.handle.QuicStreamHandler;
import com.bit.solana.p2p.peer.RoutingTable;
import com.bit.solana.p2p.peer.Settings;
import com.bit.solana.p2p.protocol.ProtocolEnum;
import com.bit.solana.p2p.protocol.ProtocolRegistry;
import com.bit.solana.p2p.protocol.impl.NetworkHandshakeHandler;
import com.bit.solana.p2p.protocol.impl.PingHandler;
import com.bit.solana.p2p.protocol.impl.TextHandler;
import com.bit.solana.util.MultiAddress;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.epoll.EpollChannelOption;
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
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.security.cert.CertificateException;
import java.util.concurrent.TimeUnit;

import static com.bit.solana.config.CommonConfig.*;

@Slf4j
@Data
@Component
@Order(1) // 确保服务端优先于客户端初始化
public class PeerServiceImpl implements PeerService {
    @Autowired
    private Settings settings;
    @Autowired
    private SystemConfig config;
    @Autowired
    private CommonConfig commonConfig;
    @Autowired
    private RoutingTable routingTable;
    @Autowired
    private QuicConnHandler quicConnHandler;
    @Autowired
    private QuicStreamHandler quicStreamHandler;


    @Autowired
    private ProtocolRegistry protocolRegistry;

    // QUIC服务器通道 （共享给客户端）
    private Channel quicServerChannel;
    // 事件循环组 （客户端复用）
    private NioEventLoopGroup eventLoopGroup;
    //QUIC SSL上下文
    private QuicSslContext sslContext;
    private SelfSignedCertificate selfSignedCert;
    private ChannelHandler quicCodec;

    private Bootstrap bootstrap;

    @Autowired
    private NetworkHandshakeHandler networkHandshakeHandler;
    @Autowired
    private PingHandler pingHandler;
    @Autowired
    private TextHandler textHandler;



    @PostConstruct
    @Override
    public void init() throws IOException, CertificateException {
        //在项目启动前 用socket 且用8333端口去访问STUN服务器 或者中转服务器 让他们返回 你的公网IP和映射地址 然后再去连接 并主动上报


        // 启动QUIC服务器
        startQuicServer();

        //连接引导节点 连接成功发起握手  A发起与X的连接 网络底层连接成功 发起握手 合适就记录 不合适就互相删除 不再打扰

        //连接成功后 握手时能知道对方是内网节点还是外网节点 握手信息会携带自己的本地IP和端口 和netty的IP和端口一对比即可知道

        //A连接X成功后 通过X发现B(且记录B来自与X) A知道B是内网节点 此时A连接B时 同时向X发送一转发消息 X转发给B B收到后也连接A A收到B的回复 此时打洞成功

        //若A2秒内未收到则降级为中继 A标记B 为需要中继才能通信  A每次发送B的消息都通过X B收到后知道这条消息来自于A且通过X转发 B回复A 也通过X转发


        MultiAddress address = MultiAddress.build(
                "ip4",
                "127.0.0.1",
                MultiAddress.Protocol.QUIC,
                commonConfig.getSelf().getPort(),
                Base58.encode(commonConfig.getSelf().getId())
        );
        System.out.println("地址：" + address.toRawAddress());

        //注册协议
        protocolRegistry.registerResultHandler(ProtocolEnum.Network_handshake_V1,  networkHandshakeHandler);
        protocolRegistry.registerResultHandler(ProtocolEnum.PING_V1,  pingHandler);

        protocolRegistry.registerResultHandler(ProtocolEnum.TEXT_V1,  textHandler);
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

            quicCodec = new QuicServerCodecBuilder()
                    .sslContext(sslContext)
                    .maxIdleTimeout(CONNECT_KEEP_ALIVE_SECONDS, TimeUnit.SECONDS)
                    .initialMaxData(50 * 1024 * 1024)
                    .initialMaxStreamDataBidirectionalLocal(5 * 1024 * 1024)
                    .initialMaxStreamDataBidirectionalRemote(5 * 1024 * 1024)
                    // 这里设置「双向流」的初始最大数量上限
                    .initialMaxStreamsBidirectional(MAX_STREAM_COUNT)
                    .initialMaxStreamsUnidirectional(MAX_STREAM_COUNT)
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
            bootstrap = new Bootstrap();
            quicServerChannel = bootstrap.group(eventLoopGroup)
                    .channel(NioDatagramChannel.class)
                    .option(ChannelOption.SO_REUSEADDR, true)
                    .option(ChannelOption.SO_RCVBUF, 10 * 1024 * 1024)
                    .option(ChannelOption.SO_SNDBUF, 10 * 1024 * 1024)
                    .handler(quicCodec)
                    .bind(commonConfig.getSelf().getPort())
                    .sync()
                    .channel();

            log.info("QUIC服务器启动成功，监听端口: {}", commonConfig.getSelf().getPort());
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

        // 6. 关闭QUIC服务器Channel
        if (quicServerChannel != null) {
            try {
                quicServerChannel.close().sync();
                log.info("QUIC服务器Channel已关闭");
            } catch (InterruptedException e) {
                log.warn("关闭QUIC Channel时线程中断", e);
                Thread.currentThread().interrupt();
            } finally {
                quicServerChannel = null;
            }
        }

        // 7. 关闭QUIC EventLoopGroup
        if (eventLoopGroup != null) {
            eventLoopGroup.shutdownGracefully(1, 5, TimeUnit.SECONDS)
                    .addListener(future -> log.info("QUIC EventLoopGroup已关闭"));
            eventLoopGroup = null;
        }


    }
}
