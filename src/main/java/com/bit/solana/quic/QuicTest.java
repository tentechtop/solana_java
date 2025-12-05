package com.bit.solana.quic;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class QuicTest {
    private static final int PORT1 = 8333;
    private static final String SERVER_HOST = "localhost";
    private static final int SEND_INTERVAL_MS = 1000; // 定时发送间隔（1秒）
    private static final int TOTAL_SEND_COUNT = 10000000; // 总发送次数（便于观察ACK）


    private static Channel clientChannel;
    private static EventLoopGroup bossGroup;
    private static EventLoopGroup clientGroup;
    private static ScheduledFuture<?> sendTask; // 定时任务引用，用于取消


    public static void main(String[] args) throws InterruptedException {
        if (clientGroup != null) {
            clientGroup.shutdownGracefully();
        }
        clientGroup = new NioEventLoopGroup();
        try {
            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(clientGroup)
                    .channel(NioDatagramChannel.class)
                    .option(ChannelOption.SO_BROADCAST, true)
                    .handler(new ChannelInitializer<NioDatagramChannel>() {
                        @Override
                        protected void initChannel(NioDatagramChannel ch) {
                            ch.pipeline()
                                    .addLast(new QuicFrameDecoder())
                                    .addLast(new QuicFrameEncoder())
                                    .addLast(new QuicServiceHandler());
                        }
                    });

            ChannelFuture bindFuture  = bootstrap.bind(0).sync();
            if (!bindFuture.isSuccess()) {
                throw new RuntimeException("客户端 Channel 绑定失败", bindFuture.cause());
            }

            // 4. 获取绑定后的 Channel，并等待其激活
            clientChannel = bindFuture.channel();
            log.info("客户端 Channel 绑定成功，本地地址：{}", clientChannel.localAddress());




            performQuicCommunication();

        } catch (Exception e) {
            e.printStackTrace();
            clientGroup.shutdownGracefully();
        }
    }


    /**
     * 执行QUIC通信测试：创建流并定时发送数据帧
     */
    private static void performQuicCommunication() {
        try {
            InetSocketAddress inetSocketAddress = new InetSocketAddress(SERVER_HOST, PORT1);
            QuicConnection quicConnection = QuicConnectionManager.getOrCreateConnection((DatagramChannel) clientChannel, inetSocketAddress);

            String testStr = "Hello Quic! 这是测试发送的二进制数据，分片大小1280字节/帧";
            ByteBuf stringBuf = ByteBufAllocator.DEFAULT.buffer();
            stringBuf.writeBytes(testStr.getBytes());
            quicConnection.sendBinaryData(1L,stringBuf);


        } catch (Exception e) {
            log.error("QUIC通信过程异常", e);
            if (sendTask != null) {
                sendTask.cancel(false);
            }
        }
    }




}