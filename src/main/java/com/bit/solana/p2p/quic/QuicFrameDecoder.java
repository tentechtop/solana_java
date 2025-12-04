package com.bit.solana.p2p.quic;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.MessageToMessageDecoder;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;

@Slf4j
public class QuicFrameDecoder  extends MessageToMessageDecoder<DatagramPacket> {

    @Override
    protected void decode(ChannelHandlerContext ctx, DatagramPacket datagramPacket, List<Object> out) throws Exception {
        log.info("开始解码");
        ByteBuf buf  = datagramPacket.content();
        InetSocketAddress remoteAddress = datagramPacket.sender(); // 发送方地址（客户端/服务器）
        // 2. 校验数据包长度：至少包含64字节帧头，否则视为无效包
        if (buf.readableBytes() < 64) {
            ctx.fireExceptionCaught(new IllegalArgumentException("无效的QUIC帧：长度小于64字节帧头"));
            return;
        }
        try {
            // 3. 调用QuicFrame的静态decode方法完成反序列化
            QuicFrame frame = QuicFrame.decode(buf, remoteAddress);
            // 4. 将解码后的帧传递给下一个处理器
            out.add(frame);
        } catch (Exception e) {
            // 5. 处理解码异常（如格式错误、字段越界等）
            ctx.fireExceptionCaught(new RuntimeException("QUIC帧解码失败", e));
        }
    }
}
