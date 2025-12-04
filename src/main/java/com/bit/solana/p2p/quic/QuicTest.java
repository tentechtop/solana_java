package com.bit.solana.p2p.quic;

import com.bit.solana.p2p.impl.handle.PlainUdpHandler;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.buffer.ByteBuf;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class QuicTest {
    private static final int PORT1 = 8333;
    private static final String SERVER_HOST = "localhost";
    private static final int SEND_INTERVAL_MS = 5000; // 定时发送间隔（1秒）
    private static final int TOTAL_SEND_COUNT = 10000000; // 总发送次数（便于观察ACK）


    private static Channel clientChannel;
    private static EventLoopGroup bossGroup;
    private static EventLoopGroup clientGroup;
    private static ScheduledFuture<?> sendTask; // 定时任务引用，用于取消


    public static void main(String[] args) throws InterruptedException {
        cleanup();

        Thread.sleep(1000); // 等待服务端启动
        startClient();
        Thread.sleep(2000); // 等待客户端连接
        performQuicCommunication();
    }

    private static void cleanup() {
        log.info("开始清理资源...");

        // 取消定时任务（如果存在）
        if (sendTask != null && !sendTask.isCancelled()) {
            sendTask.cancel(false);
            log.info("定时发送任务已取消");
        }



    }


    /**
     * 执行QUIC通信测试：创建流并定时发送数据帧
     */
    private static void performQuicCommunication() {
        try {
            if (clientChannel.isActive()){
                log.info("客户端已连接");
            }else {
                log.info("客户端已断开");
                return;
            }


            //创建连接
            QuicConnection connection = QuicConnectionManager.getOrCreateConnection((DatagramChannel)clientChannel,
                    new InetSocketAddress(SERVER_HOST, PORT1));


            DatagramChannel channel = connection.getChannel();
            if (channel != null){
                log.info("连接不是空");
                //是否可用
                if (channel.isActive()) {
                    log.info("客户端已连接");
                }else {
                    log.info("客户端已断开");
                }
            }else {
                log.info("客户端未连接");
            }


            QuicStream newStream = connection.createNewStream();


            // 初始化发送计数器
            AtomicInteger sendCount = new AtomicInteger(0);

            // 获取客户端Channel的EventLoop，用于调度定时任务（符合Netty线程模型）
            EventLoop eventLoop = clientChannel.eventLoop();

            // 调度定时发送任务
            sendTask = eventLoop.scheduleAtFixedRate(() -> {
                try {
                    int index = sendCount.getAndIncrement();
                    if (index >= TOTAL_SEND_COUNT) {
                        // 达到发送次数，取消任务
                        sendTask.cancel(false);
                        log.info("已完成{}次发送，定时任务取消", TOTAL_SEND_COUNT);
                        return;
                    }
                    // 发送数据帧
                    sendTestData(newStream, index);
                } catch (Exception e) {
                    log.error("定时发送任务异常", e);
                    sendTask.cancel(false); // 异常时取消任务
                }
            }, 0, SEND_INTERVAL_MS, TimeUnit.MILLISECONDS); // 立即开始，间隔1秒

        } catch (Exception e) {
            log.error("QUIC通信过程异常", e);
            if (sendTask != null) {
                sendTask.cancel(false);
            }
        }
    }

    /**
     * 向指定流发送测试数据
     */
    private static void sendTestData(QuicStream stream, int index) {
        QuicFrame frame = QuicFrame.acquire();

        // 设置帧信息
        frame.setFrameType(QuicConstants.FRAME_TYPE_DATA);
        frame.setStreamId(stream.getStreamId());
        frame.setConnectionId(stream.getConnection().getConnectionId());
        frame.setSequence(stream.getSendSequence().getAndIncrement());

        // 测试数据载荷
        ByteBuf payload = QuicConstants.ALLOCATOR.buffer();
        String message = "Hello QUIC! This is test message " + index;
        payload.writeBytes(message.getBytes());
        frame.setPayload(payload);

        // 目标地址
        frame.setRemoteAddress(stream.getConnection().getRemoteAddress());
        frame.setSendTime(System.currentTimeMillis());

        // 发送帧
        stream.getConnection().sendFrame(frame);
        log.info("发送数据: {} (流ID: {}, 序列号: {})", message, stream.getStreamId(), frame.getSequence());
    }

    private static void startClient() throws InterruptedException {
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
                                    .addLast(new QuicClientHandler());
                        }
                    });

            ChannelFuture bindFuture  = bootstrap.bind(8334).sync();
            if (!bindFuture.isSuccess()) {
                throw new RuntimeException("客户端 Channel 绑定失败", bindFuture.cause());
            }

            // 4. 获取绑定后的 Channel，并等待其激活
            clientChannel = bindFuture.channel();
            log.info("客户端 Channel 绑定成功，本地地址：{}", clientChannel.localAddress());


            // Channel关闭监听器：仅打印日志，不主动关闭
            // 在startClient的bind成功后添加：
            clientChannel.closeFuture().addListener(future -> {
                log.error("===== Channel被关闭 =====");
                log.error("关闭原因：{}", future.cause() == null ? "主动关闭" : future.cause().getMessage());
                // 打印调用栈，定位谁触发了close
                log.error("关闭操作调用栈：", new RuntimeException("Channel Close Trace"));
            });



        } catch (Exception e) {
            e.printStackTrace();
            clientGroup.shutdownGracefully();
        }
    }
}