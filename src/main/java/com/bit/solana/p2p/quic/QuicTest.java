package com.bit.solana.p2p.quic;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.buffer.ByteBuf;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;

@Slf4j
public class QuicTest {
    private static final int PORT1 = 8333;
    private static final String SERVER_HOST = "localhost";


    private static Channel channel1;
    private static Channel clientChannel;

    private static Bootstrap bootstrap1;


    public static void main(String[] args) throws InterruptedException {
        // 启动服务端
/*        startServer();*/


/*        // 等待服务端启动
        Thread.sleep(1000);*/

        //创建Quic连接 创建流 发送数据
        // 启动客户端（连接到服务端8333端口）
        startClient();
        Thread.sleep(500); // 等待客户端连接完成

        // 执行QUIC通信测试
        performQuicCommunication();

    }

    private static void startServer() throws InterruptedException {
        EventLoopGroup bossGroup = new NioEventLoopGroup();

        try {
            bootstrap1 = new Bootstrap();
            bootstrap1.group(bossGroup)
                    .channel(NioDatagramChannel.class)
                    .option(ChannelOption.SO_BROADCAST, true)
                    .localAddress(new InetSocketAddress(PORT1))
                    .handler(new ChannelInitializer<NioDatagramChannel>() {
                        @Override
                        protected void initChannel(NioDatagramChannel ch) {
                            ch.pipeline()
                                    .addLast(new QuicFrameDecoder())
                                    .addLast(new QuicFrameEncoder())
                                    .addLast(new QuicHandler());
                        }
                    });

            ChannelFuture f = bootstrap1.bind().sync();
            System.out.println("QUIC服务端已启动，监听端口: " + PORT1);

            channel1 = f.channel();


        } catch (Exception e) {
            e.printStackTrace();
            bossGroup.shutdownGracefully();
        }
    }


    /**
     * 执行QUIC通信测试：创建连接、创建流、发送数据
     */
    private static void performQuicCommunication() {
        if (clientChannel  == null || !clientChannel .isActive()) {
            log.error("客户端通道未激活，无法进行通信");
            return;
        }

        try {
            // 从客户端通道获取处理器
            QuicHandler clientHandler = clientChannel .pipeline().get(QuicHandler.class);
            if (clientHandler == null) {
                log.error("获取QuicClientHandler失败");
                return;
            }

            // 等待连接初始化完成
            Thread.sleep(500);

            // 创建流（流ID=1，优先级=3，无QPS限制）
            int streamId = 1;
            byte priority = 3;
            long qpsLimit = 0;
            QuicStream stream = clientHandler.createStream(streamId, priority, qpsLimit);
            System.out.println("创建QUIC流成功，流ID: " + streamId);

            // 发送测试数据
            for (int i = 0; i < 5; i++) {
                sendTestData(stream, i);
                Thread.sleep(1000); // 间隔1秒发送一次
            }

            System.out.println("测试数据发送完成");

        } catch (Exception e) {
            log.error("QUIC通信过程发生异常", e);
        }
    }

    /**
     * 向指定流发送测试数据
     */
    private static void sendTestData(QuicStream stream, int index) {
        // 从对象池获取帧
        QuicFrame frame = QuicFrame.acquire();

        // 设置帧基本信息
        frame.setFrameType(QuicConstants.FRAME_TYPE_DATA);
        frame.setStreamId(stream.getStreamId());
        frame.setConnectionId(stream.getConnection().getConnectionId());
        frame.setSequence(stream.getSendSequence().getAndIncrement());

        // 设置测试数据载荷
        ByteBuf payload = QuicConstants.ALLOCATOR.buffer();
        String message = "Hello QUIC! This is test message " + index;
        payload.writeBytes(message.getBytes());
        frame.setPayload(payload);

        // 设置远程地址（目标服务端地址）
        frame.setRemoteAddress(stream.getConnection().getRemoteAddress());

        // 设置发送时间并发送
        frame.setSendTime(System.currentTimeMillis());
        stream.getConnection().sendFrame(frame);
        log.info("发送数据: {} (流ID: {}, 序列号: {})", message, stream.getStreamId(), frame.getSequence());
    }

    private static void startClient() throws InterruptedException {
        EventLoopGroup group = new NioEventLoopGroup();
        try {
            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(group)
                    .channel(NioDatagramChannel.class)
                    .option(ChannelOption.SO_BROADCAST, true)
                    .handler(new ChannelInitializer<NioDatagramChannel>() {
                        @Override
                        protected void initChannel(NioDatagramChannel ch) {
                            ch.pipeline()
                                    .addLast(new QuicFrameDecoder())
                                    .addLast(new QuicFrameEncoder())
                                    .addLast(new QuicHandler());
                        }
                    });

            // 连接到服务端（指定服务端地址）
            ChannelFuture f = bootstrap.connect(new InetSocketAddress(SERVER_HOST, PORT1)).sync();
            clientChannel = f.channel();
            System.out.println("QUIC客户端已连接到服务端 " + SERVER_HOST + ":" + PORT1);

            clientChannel.closeFuture().addListener(future -> {
                System.out.println("QUIC客户端关闭");
                group.shutdownGracefully();
            });
        } catch (Exception e) {
            e.printStackTrace();
            group.shutdownGracefully();
        }
    }
}