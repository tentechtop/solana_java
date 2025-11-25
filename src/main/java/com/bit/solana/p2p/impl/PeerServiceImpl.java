package com.bit.solana.p2p.impl;

import com.bit.solana.config.CommonConfig;
import com.bit.solana.config.SystemConfig;
import com.bit.solana.p2p.PeerService;
import com.bit.solana.p2p.impl.handle.QuicConnHandler;
import com.bit.solana.p2p.impl.handle.QuicStreamHandler;
import com.bit.solana.p2p.impl.handle.StunHandler;
import com.bit.solana.p2p.peer.RoutingTable;
import com.bit.solana.p2p.peer.Settings;
import com.bit.solana.p2p.protocol.ProtocolEnum;
import com.bit.solana.p2p.protocol.ProtocolRegistry;
import com.bit.solana.p2p.protocol.impl.NetworkHandshakeHandler;
import com.bit.solana.p2p.protocol.impl.PingHandler;
import com.bit.solana.util.MultiAddress;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
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
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static com.bit.solana.config.CommonConfig.*;

@Slf4j
@Data
@Component
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

    // QUIC服务器通道
    private Channel quicServerChannel;
    // 事件循环组
    private NioEventLoopGroup eventLoopGroup;
    //QUIC SSL上下文
    private QuicSslContext sslContext;
    private SelfSignedCertificate selfSignedCert;

    @Autowired
    private NetworkHandshakeHandler networkHandshakeHandler;
    @Autowired
    private PingHandler pingHandler;



    //TCP服务  长连接 能实时获取数据  节点间主动维持持久连接 + 事件驱动的实时广播  这样能随时获取如何消息 以及任何变化
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private ServerBootstrap tcpBootstrap;
    private ChannelFuture tcpBindFuture;

    private Bootstrap clientBootstrap;
    private NioEventLoopGroup clientGroup;
    private Channel stunChannel; // 保存连接的Channel引用
    private ScheduledFuture<?> sendTask; // 调度任务引用，用于停止

    @PostConstruct
    @Override
    public void init() throws IOException, CertificateException {
        //在项目启动前 用socket 且用8333端口去访问STUN服务器 或者中转服务器 让他们返回 你的公网IP和映射地址 然后再去连接 并主动上报

        new Thread(() -> {
            try {
                startStun();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();



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
    }

    //start Stun
    private void startStun() {
        try {
            if (config.getIsStun()){
                //是STUN服务器
                //启动一个TCP服务器
                bossGroup = new NioEventLoopGroup();
                workerGroup = new NioEventLoopGroup();
                tcpBootstrap = new ServerBootstrap();
                tcpBootstrap.group(bossGroup, workerGroup)
                        .channel(NioServerSocketChannel.class)
                        .option(ChannelOption.SO_RCVBUF, 5* 1024 * 1024) // 接收缓冲区
                        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                        .option(ChannelOption.SO_BACKLOG, 1024)
                        .childOption(ChannelOption.SO_KEEPALIVE, true)
                        .childHandler(new ChannelInitializer<SocketChannel>() {
                            @Override
                            public void initChannel(SocketChannel ch) throws Exception {
                                ChannelPipeline pipeline = ch.pipeline();
                                pipeline.addLast(new StunHandler(true));
                            }
                        });
                tcpBindFuture = tcpBootstrap.bind("0.0.0.0",config.getStunPort()).sync();
                log.info("TCP服务已启动，端口: {}", commonConfig.getSelf().getPort());
            }else {
                //启动一个TCP客户端 去连接STUN服务 拿到自己的公网IP和映射地址
                clientGroup = new NioEventLoopGroup();
                clientBootstrap = new Bootstrap();

                clientBootstrap.group(clientGroup)
                        .channel(NioSocketChannel.class)
                        // 3. TCP参数优化
                        .option(ChannelOption.SO_REUSEADDR, true) // 允许端口复用
                        .option(ChannelOption.SO_RCVBUF, 10* 1024 * 1024) // 接收缓冲区1MB
                        .handler(new ChannelInitializer<NioSocketChannel>() {
                            @Override
                            protected void initChannel(NioSocketChannel ch) throws Exception {
                                ChannelPipeline pipeline = ch.pipeline();
                                pipeline.addLast(new StunHandler(false));
                            }
                        });
                clientBootstrap.localAddress(new InetSocketAddress(8333)); // 正确！
                log.info("TCP客户端已启动，端口: {}", 8333);

                InetSocketAddress stunAddress = new InetSocketAddress("127.0.0.1", 3479);
                ChannelFuture connectFuture = clientBootstrap.connect(stunAddress);
                //发送 0x01数据给服务端
                // 2. 同步等待连接成功（也可用addListener异步处理）
                connectFuture.sync(); // 阻塞直到连接完成（失败会抛异常）


                if (connectFuture.isSuccess()) {
                    stunChannel = connectFuture.channel();
                    log.info("成功连接STUN服务器 {}:{}，本地端口:8333",
                            stunAddress.getAddress(), stunAddress.getPort());

                    // 启动周期性发送任务（每10秒发送一次0x01）
                    startPeriodicSendTask();

                    // 等待连接关闭（阻塞，直到主动关闭/服务端断开）
                    stunChannel.closeFuture().sync();
                } else {
                    log.error("连接STUN服务器失败: {}", connectFuture.cause().getMessage());
                }

            }
        }catch (Exception e){
            log.error("TCP服务启动失败",e);
        }
    }




    private void startPeriodicSendTask() {
        // 使用GLOBAL_SCHEDULER调度：延迟0秒执行，每10秒重复
        sendTask = GLOBAL_SCHEDULER.scheduleAtFixedRate(
                this::send0x01Data,
                0, // 初始延迟（立即执行第一次）
                10, // 周期间隔
                TimeUnit.SECONDS
        );
    }

    /**
     * 发送0x01二进制数据到STUN服务器
     */
    private void send0x01Data() {
        // 检查Channel是否存活（避免发送到已关闭的连接）
        if (stunChannel == null || !stunChannel.isActive() || !stunChannel.isWritable()) {
            log.warn("Channel不可用，停止发送任务");
            stopPeriodicSendTask(); // 连接断开则停止任务
            return;
        }

        // 创建ByteBuf并写入0x01
        ByteBuf dataBuf = stunChannel.alloc().buffer(1);
        dataBuf.writeByte(0x01);

        // 发送数据并监听结果
        stunChannel.writeAndFlush(dataBuf).addListener(future -> {
            if (future.isSuccess()) {
                log.info("成功发送0x01数据到STUN服务器（{}）", System.currentTimeMillis());
            } else {
                log.error("发送0x01数据失败", future.cause());
                stopPeriodicSendTask(); // 发送失败停止任务
            }
        });
    }

    /**
     * 停止周期性发送任务
     */
    private void stopPeriodicSendTask() {
        if (sendTask != null && !sendTask.isCancelled()) {
            sendTask.cancel(true); // 取消任务
            log.info("周期性发送任务已停止");
        }
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
            Bootstrap bootstrap = new Bootstrap();
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
        if (quicServerChannel != null) {
            quicServerChannel.closeFuture().sync();
        }
        if (eventLoopGroup != null) {
            eventLoopGroup.shutdownGracefully();
        }
        log.info("QUIC服务器已关闭");
    }
}
