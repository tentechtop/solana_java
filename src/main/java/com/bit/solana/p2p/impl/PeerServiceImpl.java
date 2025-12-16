package com.bit.solana.p2p.impl;

import com.bit.solana.config.CommonConfig;
import com.bit.solana.config.SystemConfig;
import com.bit.solana.p2p.PeerService;
import com.bit.solana.p2p.impl.handle.PlainUdpHandler;
import com.bit.solana.p2p.peer.RoutingTable;
import com.bit.solana.p2p.peer.Settings;
import com.bit.solana.p2p.protocol.ProtocolEnum;
import com.bit.solana.p2p.protocol.ProtocolRegistry;
import com.bit.solana.p2p.protocol.impl.NetworkHandshakeHandler;
import com.bit.solana.p2p.protocol.impl.PingHandler;
import com.bit.solana.p2p.protocol.impl.TextHandler;
import com.bit.solana.quic.*;
import com.bit.solana.util.MultiAddress;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.ssl.util.SelfSignedCertificate;


import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.Base58;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.cert.CertificateException;
import java.util.concurrent.TimeUnit;

import static com.bit.solana.quic.QuicConnectionManager.Global_Channel;
import static com.bit.solana.quic.QuicConnectionManager.connectRemote;
import static com.bit.solana.quic.QuicConstants.ALLOCATOR;
import static com.bit.solana.quic.QuicConstants.generator;

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
    private ProtocolRegistry protocolRegistry;

    // QUIC服务器通道
    private Channel quicServerChannel;
    // 事件循环组
    private NioEventLoopGroup eventLoopGroup;

    private SelfSignedCertificate selfSignedCert;
    private ChannelHandler serviceCodec;
    private ChannelHandler clientCodec;

    private Bootstrap bootstrap;

    @Autowired
    private NetworkHandshakeHandler networkHandshakeHandler;
    @Autowired
    private PingHandler pingHandler;
    @Autowired
    private TextHandler textHandler;

    @Autowired
    private PlainUdpHandler plainUdpHandler;



    @PostConstruct
    @Override
    public void init() throws IOException, CertificateException, InterruptedException {
        //在项目启动前 用socket 且用8333端口去访问STUN服务器 或者中转服务器 让他们返回 你的公网IP和映射地址 然后再去连接 并主动上报

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


    public void startQuicServer()  {
        // 1. 创建事件循环组（UDP无连接，仅需一个线程组）
        //可用核心的一半
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        eventLoopGroup = new NioEventLoopGroup(availableProcessors);
        try {
            // 2. 配置Bootstrap（UDP使用Bootstrap而非ServerBootstrap）
            bootstrap = new Bootstrap();
            bootstrap.group(eventLoopGroup)
                    .channel(NioDatagramChannel.class) // UDP通道类型
                    .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT) // 池化内存分配（减少GC）
                    .option(ChannelOption.SO_RCVBUF, 64*1024*1024) // 接收缓冲区
                    .option(ChannelOption.SO_REUSEADDR, true) // 允许端口复用（多线程共享端口）
                    .option(ChannelOption.SO_BROADCAST, true) // 支持广播（按需开启）
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3000) // UDP无连接，超时设短（30秒）
                    .option(ChannelOption.RCVBUF_ALLOCATOR, new FixedRecvByteBufAllocator(1024 * 64)) // 固定接收缓冲区大小（64KB，减少动态调整开销）

                    .handler(new ChannelInitializer<NioDatagramChannel>() {
                        @Override
                        protected void initChannel(NioDatagramChannel ch) throws Exception {
                            ChannelPipeline pipeline = ch.pipeline();
                            pipeline.addLast(new QuicServiceHandler());
                        }
                    });

            // 3. 绑定端口并启动
            ChannelFuture sync = bootstrap.bind(commonConfig.getSelf().getPort()).sync();
            log.info("QUIC服务器已启动，监听端口：{}", commonConfig.getSelf().getPort());
            Channel channel = sync.channel();

            Global_Channel = (DatagramChannel) channel;

            //连接到节点8334
            if (commonConfig.getSelf().getPort()==8333){
                InetSocketAddress targetAddr = new InetSocketAddress("127.0.0.1", 8334);
                QuicConnection quicConnection = connectRemote(targetAddr);



            }


        }catch (Exception e){
            e.printStackTrace();
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
