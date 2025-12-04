package com.bit.solana.p2p.quic;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * FEC编码器（XOR冗余）
 */

public class FecEncoder {
    private final List<QuicFrame> groupFrames = new ArrayList<>();
    private int groupId = 0;

    public void addFrame(QuicFrame frame) {
        if (frame == null || frame.getPayload() == null) {
            return; // 过滤空帧/空负载
        }
        groupFrames.add(frame);
        frame.setFecGroupId(groupId);
        frame.setFecIndex(groupFrames.size() - 1);
    }

    public boolean isGroupFull() {
        return groupFrames.size() >= QuicConstants.FEC_REDUNDANCY_RATIO;
    }

    public QuicFrame encode() {
        if (groupFrames.isEmpty()) {
            return null;
        }

        // XOR所有帧的payload生成冗余数据
        ByteBuf fecPayload = null;
        try {
            for (QuicFrame frame : groupFrames) {
                ByteBuf payload = frame.getPayload();
                if (payload == null || !payload.isReadable()) {
                    continue;
                }

                // 初始化FEC负载为第一个有效负载的拷贝
                if (fecPayload == null) {
                    fecPayload = payload.copy();
                    continue;
                }

                // 对当前负载和FEC负载进行XOR异或操作
                fecPayload = xorByteBuf(fecPayload, payload);
            }

            // 无有效负载时返回null
            if (fecPayload == null || !fecPayload.isReadable()) {
                return null;
            }

            // 创建FEC帧
            QuicFrame fecFrame = QuicFrame.acquire();
            QuicFrame firstFrame = groupFrames.get(0);
            fecFrame.setConnectionId(firstFrame.getConnectionId());
            fecFrame.setStreamId(firstFrame.getStreamId());
            fecFrame.setFrameType(QuicConstants.FRAME_TYPE_FEC);
            fecFrame.setFecGroupId(groupId);
            fecFrame.setFecIndex(QuicConstants.FEC_REDUNDANCY_RATIO);
            fecFrame.setPayload(fecPayload);
            fecFrame.setRemoteAddress(firstFrame.getRemoteAddress());

            // 重置分组（注意：groupId自增，保证分组唯一）
            groupFrames.clear();
            groupId++;

            return fecFrame;
        } catch (Exception e) {
            // 异常时释放内存，避免泄漏
            if (fecPayload != null) {
                fecPayload.release();
            }
            throw new RuntimeException("FEC编码失败", e);
        }
    }

    /**
     * 核心XOR异或方法：对两个ByteBuf按字节异或，返回新的ByteBuf（需手动释放）
     * 规则：
     * 1. 异或长度以较短的ByteBuf为准
     * 2. 保持结果的读写索引为0（可直接写入/读取）
     * 3. 原ByteBuf的读写索引不会被修改
     *
     * @param buf1 源Buf1（会被读取，不修改原索引）
     * @param buf2 源Buf2（会被读取，不修改原索引）
     * @return 异或后的新ByteBuf（需手动release）
     */
    private ByteBuf xorByteBuf(ByteBuf buf1, ByteBuf buf2) {
        // 保存原索引，避免修改输入Buf的读写状态
        int buf1ReaderIndex = buf1.readerIndex();
        int buf2ReaderIndex = buf2.readerIndex();

        try {
            // 重置读取索引，从0开始读取
            buf1.readerIndex(0);
            buf2.readerIndex(0);

            // 取两个Buf的最小可读长度
            int minLength = Math.min(buf1.readableBytes(), buf2.readableBytes());
            ByteBuf result = Unpooled.buffer(minLength);

            // 逐字节异或
            for (int i = 0; i < minLength; i++) {
                byte b1 = buf1.readByte();
                byte b2 = buf2.readByte();
                result.writeByte(b1 ^ b2); // 核心XOR操作
            }

            // 重置结果的读写索引，方便后续使用
            result.readerIndex(0);
            result.writerIndex(minLength);

            // 释放原buf1（因为要返回新的result，避免内存泄漏）
            buf1.release();

            return result;
        } finally {
            // 恢复原Buf的读取索引
            buf1.readerIndex(buf1ReaderIndex);
            buf2.readerIndex(buf2ReaderIndex);
        }
    }

    /**
     * 重置分组（可选：用于手动清空未完成的分组）
     */
    public void resetGroup() {
        groupFrames.clear();
        // groupId 不重置，保证全局唯一
    }
}