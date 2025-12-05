package com.bit.solana.quic;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;

/**
 * UDP服务端启动类
 */
public class UdpServer {
    // 服务端端口


    public void run() throws InterruptedException {
        // 1. 创建事件循环组（UDP无连接，仅需一个线程组）
        EventLoopGroup group = new NioEventLoopGroup();
        try {
            // 2. 配置Bootstrap（UDP使用Bootstrap而非ServerBootstrap）
            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(group)
                    .channel(NioDatagramChannel.class) // UDP通道类型
                    .option(ChannelOption.SO_BROADCAST, true) // 允许广播
                    .handler(new ChannelInitializer<NioDatagramChannel>() {
                        @Override
                        protected void initChannel(NioDatagramChannel ch) {
                            ch.pipeline()
                                    .addLast(new QuicFrameDecoder())
                                    .addLast(new QuicFrameEncoder())
                                    .addLast(new QuicServiceHandler());
                        }
                    });

            // 3. 绑定端口并启动
            bootstrap.bind(8333).sync().sync();
        } finally {
            // 4. 优雅关闭线程组

        }
    }

    public static void main(String[] args) throws InterruptedException {
        System.out.println("UDP服务端启动中...");
        new UdpServer().run();
    }
}