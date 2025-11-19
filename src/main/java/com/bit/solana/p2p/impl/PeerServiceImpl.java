package com.bit.solana.p2p.impl;

import com.bit.solana.p2p.peer.Peer;
import com.bit.solana.p2p.PeerService;
import com.bit.solana.p2p.peer.RoutingTable;
import com.bit.solana.p2p.peer.Settings;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.incubator.codec.quic.*;
import io.netty.util.CharsetUtil;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.bit.solana.util.ByteUtils.bytesToHex;
import static com.bit.solana.util.ECCWithAESGCM.generateCurve25519KeyPair;
import static org.bouncycastle.crypto.util.OpenSSHPublicKeyUtil.encodePublicKey;

@Slf4j
@Component
public class PeerServiceImpl implements PeerService {
    // 消息过期时间
    public static final long MESSAGE_EXPIRATION_TIME = 30;//秒
    // 节点过期时间
    public static final long NODE_EXPIRATION_TIME = 60;//秒
    //节点信息
    private Peer self;//本地节点信息
    // QUIC服务器通道
    private Channel quicServerChannel;
    // EventLoopGroup
    private NioEventLoopGroup eventLoopGroup;

    @Autowired
    private Settings settings;

    @Autowired
    private RoutingTable routingTable;

    // 已连接的节点列表 节点ID
    public  Map<byte[], QuicChannel> peerNodes = new HashMap<>();


    /**
     * QuicChannel - > 结构
     */
    public class QuicNode {
        //出站还是入站
        private boolean isOutbound;
        private QuicChannel quicChannel;
    }


    /**
     * 初始化节点信息
     */
    @PostConstruct
    public void init() throws NoSuchAlgorithmException, CertificateException {
        self = new Peer();
        self.setPort(8333);
        byte[][] aliceKeys = generateCurve25519KeyPair();
        byte[] alicePrivateKey = aliceKeys[0];
        byte[] alicePublicKey = aliceKeys[1];
        self.setId(alicePublicKey);
        self.setPrivateKey(alicePrivateKey);


        log.info("本地节点初始化完成，公钥: {}", bytesToHex(self.getId()));


        // 启动QUIC服务器
        startQuicServer();

    }

    /**
     * 启动QUIC服务器
     */
    public void startQuicServer() throws CertificateException {
        eventLoopGroup = new NioEventLoopGroup(1);

        try {
            // 创建自签名证书
            SelfSignedCertificate selfSignedCert = new SelfSignedCertificate();

            // 构建QUIC SSL上下文
            QuicSslContext sslContext = QuicSslContextBuilder.forServer(
                            selfSignedCert.privateKey(), null, selfSignedCert.certificate())
                    .applicationProtocols("solana-p2p") // 自定义应用层协议
                    .build();

            // 构建QUIC服务器编解码器
            ChannelHandler quicCodec = new QuicServerCodecBuilder()
                    .sslContext(sslContext)
                    .maxIdleTimeout(5, TimeUnit.SECONDS)
                    .initialMaxData(10 * 1024 * 1024) // 10MB
                    .initialMaxStreamDataBidirectionalLocal(1 * 1024 * 1024) // 1MB
                    .initialMaxStreamDataBidirectionalRemote(1 * 1024 * 1024) // 1MB
                    .initialMaxStreamsBidirectional(100)
                    .initialMaxStreamsUnidirectional(100)
                    .tokenHandler(InsecureQuicTokenHandler.INSTANCE) // 开发环境使用，生产环境需自定义
                    .handler(new QuicConnectionHandler()) // 处理QUIC连接
                    .streamHandler(new QuicStreamInitializer()) // 处理流
                    .build();

            // 启动服务器
            Bootstrap bootstrap = new Bootstrap();
            quicServerChannel = bootstrap.group(eventLoopGroup)
                    .channel(NioDatagramChannel.class)
                    .handler(quicCodec)
                    .bind(self.getPort())
                    .sync()
                    .channel();

            log.info("QUIC服务器启动成功，监听地址: {}", self.getPort());

        } catch (Exception e) {
            log.error("启动QUIC服务器失败", e);
            throw new RuntimeException("QUIC服务器启动失败", e);
        }
    }

    /**
     * 处理QUIC连接的处理器
     */
    private class QuicConnectionHandler extends ChannelInboundHandlerAdapter  {
        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            QuicChannel quicChannel = (QuicChannel) ctx.channel();
            // 正确获取远程地址：先转为 QuicConnectionAddress，再提取 InetSocketAddress
            QuicConnectionAddress quicAddr = (QuicConnectionAddress) quicChannel.remoteAddress();

            log.info("新的QUIC连接建立: {}", quicAddr);


        }



        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            QuicChannel quicChannel = (QuicChannel) ctx.channel();
            QuicConnectionAddress quicAddr = (QuicConnectionAddress) quicChannel.remoteAddress();

            log.info("QUIC连接关闭: {}", quicAddr);


        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.error("QUIC连接异常", cause);
            ctx.close();
        }

        @Override
        public boolean isSharable() {
            return true;
        }
    }

    /**
     * QUIC流初始化器
     */
    private class QuicStreamInitializer extends ChannelInitializer<QuicStreamChannel> {
        @Override
        protected void initChannel(QuicStreamChannel ch) {
            ch.pipeline().addLast(new QuicStreamHandler());
        }
    }


    /**
     * 二进制流处理器（核心修改：读写二进制数据）
     */
    private class QuicStreamHandler extends ChannelInboundHandlerAdapter {
        // 接收二进制数据
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            ByteBuf byteBuf = (ByteBuf) msg;
            try {

                // 核心修复：先转为 QuicStreamAddress，再提取 InetSocketAddress
                QuicStreamChannel streamChannel = (QuicStreamChannel) ctx.channel();
                QuicStreamAddress quicStreamAddr = (QuicStreamAddress) streamChannel.remoteAddress();


                log.info("收到来自 的二进制数据，来自: {}", quicStreamAddr);

                // 读取二进制数据（转为byte数组，方便后续处理）
                byte[] receivedData = new byte[byteBuf.readableBytes()];
                byteBuf.readBytes(receivedData);


                log.info("收到来自 的二进制数据，长度: {} 字节，内容: {}",
                        receivedData.length, bytesToHex(receivedData));


            } finally {
                byteBuf.release(); // 必须释放缓冲区
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.error("二进制流处理异常", cause);
            ctx.close();
        }
    }

    /**
     * 处理收到的二进制数据（业务逻辑扩展点）
     */
    private void handleReceivedBinaryData(InetSocketAddress sender, byte[] data) {
        // 示例业务：根据二进制协议头判断消息类型、更新路由表等
        // 1. 解析data[0]作为消息类型（如0x01=心跳，0x02=数据同步）
        // 2. 转发数据到其他节点（routingTable.getPeers()）
        // 3. 存储数据到本地等
    }

    /**
     * 构建二进制响应（示例：前缀+原数据回声）
     */
    private byte[] buildBinaryResponse(byte[] originalData) {
        byte[] prefix = new byte[]{0x00, 0x01}; // 自定义响应标识（2字节）
        byte[] response = new byte[prefix.length + originalData.length];
        System.arraycopy(prefix, 0, response, 0, prefix.length);
        System.arraycopy(originalData, 0, response, prefix.length, originalData.length);
        return response;
    }




    /**
     * 处理收到的消息
     */
    private void handleReceivedMessage(InetSocketAddress sender, String message) {
        // 实现消息处理逻辑，例如：
        // 1. 解析消息类型
        // 2. 更新路由表
        // 3. 转发消息
        // 4. 处理特定命令等
    }


    /**
     * 关闭服务器
     */
    public void shutdown() {
        if (quicServerChannel != null) {
            quicServerChannel.close();
        }
        if (eventLoopGroup != null) {
            eventLoopGroup.shutdownGracefully();
        }
        log.info("QUIC服务器已关闭");
    }






}
