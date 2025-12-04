package com.bit.solana.p2p.quic;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class ByteBufXorUtil {
    /**
     * 通用ByteBuf异或工具方法
     */
    public static ByteBuf xor(ByteBuf buf1, ByteBuf buf2) {
        if (buf1 == null || !buf1.isReadable()) {
            return buf2 == null ? Unpooled.EMPTY_BUFFER : buf2.copy();
        }
        if (buf2 == null || !buf2.isReadable()) {
            return buf1.copy();
        }

        int minLen = Math.min(buf1.readableBytes(), buf2.readableBytes());
        ByteBuf result = Unpooled.buffer(minLen);

        int r1 = buf1.readerIndex();
        int r2 = buf2.readerIndex();
        try {
            buf1.readerIndex(0);
            buf2.readerIndex(0);

            for (int i = 0; i < minLen; i++) {
                result.writeByte(buf1.readByte() ^ buf2.readByte());
            }
            result.readerIndex(0);
            return result;
        } finally {
            buf1.readerIndex(r1);
            buf2.readerIndex(r2);
        }
    }
}
