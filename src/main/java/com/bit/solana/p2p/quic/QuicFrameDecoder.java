package com.bit.solana.p2p.quic;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.MessageToMessageDecoder;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.List;

@Slf4j
public class QuicFrameDecoder extends MessageToMessageDecoder<DatagramPacket> {

    @Override
    protected void decode(ChannelHandlerContext ctx, DatagramPacket datagramPacket, List<Object> out) throws Exception {
        log.info("开始解码");
        //如果一个远程地址出现大量无效帧就断开连接
        ByteBuf buf = datagramPacket.content();
        InetSocketAddress remoteAddress = datagramPacket.sender(); // 发送方地址（客户端/服务器）

        // 1. 校验数据包长度：至少包含64字节帧头，无效则直接丢弃
        if (buf.readableBytes() < 64) {
            log.warn("丢弃无效QUIC帧：长度小于64字节帧头，实际长度={}", buf.readableBytes());
            return;
        }

        try {
            // 2. 调用QuicFrame的静态decode方法完成反序列化
            QuicFrame frame = QuicFrame.decode(buf, remoteAddress);
            // 3. 将解码后的帧传递给下一个处理器
            out.add(frame);
        } catch (Exception e) {
            // 4. 解码失败直接丢弃，仅记录日志
            log.warn("QUIC帧解码失败，已丢弃无效数据包", e);
        }
    }
}