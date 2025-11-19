package com.bit.solana.p2p.impl;

import io.netty.channel.ChannelInitializer;
import io.netty.incubator.codec.quic.QuicStreamChannel;

public class QuicStreamInitializer extends ChannelInitializer<QuicStreamChannel> {
    @Override
    protected void initChannel(QuicStreamChannel ch) throws Exception {
        ch.pipeline().addLast(new QuicStreamHandler());
    }
}
