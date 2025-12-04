package com.bit.solana.p2p.quic;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.EncoderException;
import io.netty.handler.codec.MessageToByteEncoder;
import io.netty.handler.codec.MessageToMessageEncoder;

import java.io.Serializable;
import java.util.List;

public class QuicFrameEncoder extends MessageToMessageEncoder<QuicFrame> {

    @Override
    protected void encode(ChannelHandlerContext channelHandlerContext, QuicFrame quicFrame, List<Object> out) throws Exception {
        byte[] encode = quicFrame.encode();
        out.add(encode);
    }
}
