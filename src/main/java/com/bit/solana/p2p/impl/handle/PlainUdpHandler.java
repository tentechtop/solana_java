package com.bit.solana.p2p.impl.handle;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.util.Arrays;

import static com.bit.solana.util.ByteUtils.bytesToHex;

/**
 * 只处理普通 UDP 数据包（前缀 0x01,0x01,0x01,0x01），其余全部透传
 */
@Slf4j
@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
@ChannelHandler.Sharable
// 关键：替换为 ChannelInboundHandlerAdapter，禁用自动释放
public class PlainUdpHandler extends ChannelInboundHandlerAdapter {

    // 普通 UDP 包固定前缀
    public static final byte[] PLAIN_UDP_FIXED_PREFIX = new byte[]{0x01, 0x01, 0x01, 0x01,0x53, 0x4F, 0x4C, 0x41};
    private static final int PREFIX_LENGTH = PLAIN_UDP_FIXED_PREFIX.length;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        // 只处理 DatagramPacket 类型的消息
        if (!(msg instanceof DatagramPacket packet)) {
            ctx.fireChannelRead(msg); // 非UDP包透传
            return;
        }

        ByteBuf content = packet.content();
        int readableBytes = content.readableBytes();

        try {
            // 1. 空包直接透传
            if (readableBytes == 0) {
                ctx.fireChannelRead(packet);
                return;
            }

            // 2. 判断是否为普通包（前缀匹配）
            boolean isPlainUdp = false;
            if (readableBytes >= PREFIX_LENGTH) {
                byte[] prefix = new byte[PREFIX_LENGTH];
                content.getBytes(0, prefix); // 不修改读指针
                isPlainUdp = Arrays.equals(prefix, PLAIN_UDP_FIXED_PREFIX);
            }

            if (isPlainUdp) {
                // 3. 普通包：处理后手动释放（无需透传）
                InetSocketAddress sender = packet.sender();
                byte[] data = new byte[readableBytes];
                content.getBytes(0, data);

                log.info("收到普通 UDP 数据包 | 来源：{} | 长度：{} | 内容：{}",
                        sender, readableBytes, bytesToHex(data));


                //去除掉标志 并反序列化
                //请求或者回复 或者 普通消息


                // 手动释放普通包（处理完成，无需透传）
                ReferenceCountUtil.release(packet);
            } else {
                // 4. 非普通包：透传（不释放，交给后续处理器释放）
                // 关键：透传前增加引用计数（防止后续释放时计数异常）
                ReferenceCountUtil.retain(packet);
                ctx.fireChannelRead(packet);
            }
        } catch (Exception e) {
            // 异常时兜底释放，避免内存泄漏
            ReferenceCountUtil.release(packet);
            log.error("处理UDP数据包异常", e);
            throw e;
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("处理普通 UDP 数据包时异常", cause);
        // 异常透传，但不再触发重复释放
        ctx.fireExceptionCaught(cause);
    }



}