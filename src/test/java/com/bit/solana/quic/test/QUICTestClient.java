package com.bit.solana.quic.test;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.ChannelInputShutdownReadComplete;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.incubator.codec.quic.QuicChannel;
import io.netty.incubator.codec.quic.QuicClientCodecBuilder;
import io.netty.incubator.codec.quic.QuicSslContext;
import io.netty.incubator.codec.quic.QuicSslContextBuilder;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import io.netty.incubator.codec.quic.QuicStreamType;
import io.netty.util.NetUtil;

import java.net.InetSocketAddress;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * QUIC测试客户端（持续发送二进制数据）
 * author heliang
 */
public class QUICTestClient {

    // 发送间隔（毫秒），可调整
    private static final long SEND_INTERVAL = 1000;
    // 每次发送的二进制数据长度（字节），可调整
    private static final int DATA_LENGTH = 16;
    // 随机生成测试用二进制数据
    private static final Random RANDOM = new Random();

    public static void main(String[] args) throws Exception {
        // 1. 关键：应用层协议与服务器一致（solana-p2p）
        QuicSslContext sslContext = QuicSslContextBuilder.forClient()
                .trustManager(InsecureTrustManagerFactory.INSTANCE)
                .applicationProtocols("solana-p2p")
                .build();

        NioEventLoopGroup group = new NioEventLoopGroup(1);
        try {
            ChannelHandler codec = new QuicClientCodecBuilder()
                    .sslContext(sslContext)
                    .maxIdleTimeout(10, TimeUnit.SECONDS) // 延长空闲超时，适配持续发送
                    .initialMaxData(10 * 1024 * 1024) // 增大数据限制
                    .initialMaxStreamDataBidirectionalLocal(1 * 1024 * 1024)
                    .build();

            Bootstrap bs = new Bootstrap();
            Channel channel = bs.group(group)
                    .channel(NioDatagramChannel.class)
                    .handler(codec)
                    .bind(0).sync().channel();

            // 连接服务器（本地8333端口）
            QuicChannel quicChannel = QuicChannel.newBootstrap(channel)
                    .streamHandler(new ChannelInboundHandlerAdapter() {
                        @Override
                        public void channelActive(ChannelHandlerContext ctx) {
                            ctx.close();
                        }
                    })
                    .remoteAddress(new InetSocketAddress(NetUtil.LOCALHOST4, 8333))
                    .connect()
                    .get();

            System.out.println("已连接到QUIC服务器（8333端口），开始持续发送二进制数据...");

            // 2. 创建双向流（用于发送+接收服务器响应）
            QuicStreamChannel streamChannel = quicChannel.createStream(QuicStreamType.BIDIRECTIONAL,
                    new ChannelInboundHandlerAdapter() {
                        // 接收服务器的二进制响应
                        @Override
                        public void channelRead(ChannelHandlerContext ctx, Object msg) {
                            ByteBuf byteBuf = (ByteBuf) msg;
                            try {
                                byte[] responseData = new byte[byteBuf.readableBytes()];
                                byteBuf.readBytes(responseData);
                                System.out.printf("收到服务器响应：长度=%d字节，内容=%s%n",
                                        responseData.length, bytesToHex(responseData));
                            } finally {
                                byteBuf.release();
                            }
                        }

                        @Override
                        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
                            if (evt == ChannelInputShutdownReadComplete.INSTANCE) {
                                System.out.println("服务器关闭流，客户端即将退出");
                            }
                        }

                        @Override
                        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                            System.err.println("流处理异常：" + cause.getMessage());
                            cause.printStackTrace();
                            ctx.close();
                        }
                    }).sync().getNow();

            // 3. 定时任务：持续发送二进制数据
            ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();


            scheduler.scheduleAtFixedRate(() -> {
                if (streamChannel.isActive()) {
                    // 生成随机二进制测试数据（也可替换为自定义二进制内容）
                    byte[] sendData = generateRandomBinaryData();
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
            }, 0, SEND_INTERVAL, TimeUnit.MILLISECONDS);

            // 阻塞等待流关闭（可手动终止程序停止发送）
            streamChannel.closeFuture().sync();
            scheduler.shutdown();
            quicChannel.closeFuture().sync();
            channel.close().sync();

        } finally {
            group.shutdownGracefully();
            System.out.println("客户端退出");
        }
    }

    /**
     * 生成随机二进制数据（测试用）
     */
    private static byte[] generateRandomBinaryData() {
        byte[] data = new byte[DATA_LENGTH];
        RANDOM.nextBytes(data);
        return data;
    }

    /**
     * 字节数组转十六进制字符串（便于打印查看）
     */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}