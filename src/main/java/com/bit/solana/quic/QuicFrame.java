package com.bit.solana.quic;

import io.netty.buffer.ByteBuf;
import io.netty.util.Recycler;
import lombok.Data;

import java.net.InetSocketAddress;

import static com.bit.solana.quic.QuicConstants.ALLOCATOR;
import static io.netty.handler.codec.http2.Http2CodecUtil.FRAME_HEADER_LENGTH;

/**
 * 数据帧 连接下的数据
 */
@Data
public class QuicFrame {
    private long connectionId; // 雪花算法
    private long dataId; //  雪花算法
    private int total;//分片数量 4字节
    private byte frameType; // 帧类型 1字节
    private int sequence; // 序列号分片顺序 0-total 最大序列号是total-1 4字节  FIN帧是total-1  序列号是0就是第一帧 序列号是total-1就是最后一帧
    private int frameTotalLength;//载荷总长 4字节
    private ByteBuf payload; // 有效载荷
    private InetSocketAddress remoteAddress;

    // 固定头部长度 = connectionId(8) + dataId(8) + total(4) + frameType(1) + sequence(4) + frameTotalLength(4)
    public static final int FIXED_HEADER_LENGTH = 8 + 8 + 4 + 1 + 4 + 4;

    // 对象池 定义 Recycler 池，指定“新对象创建规则”
    private static final int RECYCLER_MAX_CAPACITY = 1024 * 2;
    private static final Recycler<QuicFrame> RECYCLER = new Recycler<QuicFrame>(RECYCLER_MAX_CAPACITY) {
        @Override
        protected QuicFrame newObject(Handle<QuicFrame> handle) {
            // 创建新对象时绑定 Netty 的 Handle
            return new QuicFrame(handle);
        }
    };
    private final Recycler.Handle<QuicFrame> handle;

    private QuicFrame(Recycler.Handle<QuicFrame> handle) {
        this.handle = handle;
    }

    // 从对象池获取实例
    public static QuicFrame acquire() {
        return RECYCLER.get();
    }


    /**
     * 【序列化】将当前QuicFrame写入外部传入的ByteBuf（大端序）
     * 统一序列化入口：复用外部缓冲区，适配所有传输场景（TCP/UDP）
     * @param buf 外部传入的缓冲区（需确保容量足够）
     * @throws IllegalStateException 帧无效或写入失败时抛出
     */
    public void encode(ByteBuf buf) {
        // 前置校验：帧必须有效
        if (!isValid()) {
            throw new IllegalStateException("QuicFrame is invalid, cannot encode: " + this);
        }

        try {
            // 1. 写入固定头部字段（大端序）
            buf.writeLong(connectionId);   // 8字节连接ID
            buf.writeLong(dataId);         // 8字节数据ID
            buf.writeInt(total);           // 4字节分片总数
            buf.writeByte(frameType);      // 1字节帧类型
            buf.writeInt(sequence);        // 4字节分片序列号
            buf.writeInt(frameTotalLength);// 4字节总长度

            // 2. 写入payload（有效载荷）
            if (payload != null && payload.isReadable()) {
                buf.writeBytes(payload, payload.readerIndex(), payload.readableBytes());
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to encode QuicFrame to ByteBuf", e);
        }
    }

    /**
     * 【反序列化】从ByteBuf解析QuicFrame（带远端地址，UDP场景）
     * 统一反序列化入口：从对象池获取实例，使用后需调用release()
     * @param buf 待解析的缓冲区（解析后readerIndex会移动）
     * @param remoteAddress 远端地址（UDP的DatagramPacket/TCP的Channel远端地址）
     * @return 从对象池获取的QuicFrame
     * @throws IllegalArgumentException 缓冲区数据非法时抛出
     */
    public static QuicFrame decode(ByteBuf buf, InetSocketAddress remoteAddress) {
        // 标记当前读取位置，异常时重置
        buf.markReaderIndex();

        QuicFrame frame = null;
        try {
            // 前置校验：缓冲区至少包含固定头部
            if (buf.readableBytes() < FIXED_HEADER_LENGTH) {
                throw new IllegalArgumentException(
                        "Insufficient bytes for QuicFrame header: need " + FIXED_HEADER_LENGTH
                                + ", actual " + buf.readableBytes()
                );
            }

            // 1. 从对象池获取帧实例
            frame = QuicFrame.acquire();

            // 2. 读取固定头部字段（大端序）
            frame.connectionId = buf.readLong();
            frame.dataId = buf.readLong();
            frame.total = buf.readInt();
            frame.frameType = buf.readByte();
            frame.sequence = buf.readInt();
            frame.frameTotalLength = buf.readInt();

            // 新增：前置校验frameTotalLength是否合法
            if (frame.frameTotalLength < FIXED_HEADER_LENGTH) {
                throw new IllegalArgumentException(
                        "frameTotalLength非法：必须≥固定头部长度(" + FIXED_HEADER_LENGTH + ")，实际=" + frame.frameTotalLength
                );
            }


            // 3. 校验总长度与实际缓冲区匹配
            int expectedPayloadLength = frame.frameTotalLength - FIXED_HEADER_LENGTH;
            int actualRemainingBytes = buf.readableBytes();
            if (actualRemainingBytes != expectedPayloadLength) {
                throw new IllegalArgumentException(
                        "Payload length mismatch: expected " + expectedPayloadLength
                                + ", actual " + actualRemainingBytes
                                + ", frameTotalLength=" + frame.frameTotalLength
                );
            }

            // 4. 读取payload（有效载荷）
            if (expectedPayloadLength > 0) {
                frame.payload = ALLOCATOR.buffer(expectedPayloadLength);
                buf.readBytes(frame.payload, expectedPayloadLength);
            } else {
                frame.payload = null;
            }

            // 5. 设置远端地址
            frame.remoteAddress = remoteAddress;

            // 6. 最终校验：解析后的帧必须有效
            if (!frame.isValid()) {
                throw new IllegalStateException("Deserialized QuicFrame is invalid: " + frame);
            }

            return frame;
        } catch (Exception e) {
            // 异常时重置缓冲区读取位置（不影响后续解析）
            buf.resetReaderIndex();
            // 释放已创建的帧（避免对象池泄漏）
            if (frame != null) {
                frame.release();
            }
            throw new RuntimeException("Failed to decode QuicFrame from ByteBuf", e);
        }
    }

    /**
     * 【反序列化】从ByteBuf解析QuicFrame（无远端地址，TCP场景重载）
     * @param buf 待解析的缓冲区
     * @return 从对象池获取的QuicFrame
     */
    public static QuicFrame decode(ByteBuf buf) {
        return decode(buf, null);
    }

    public void release() {
        // 释放ByteBuf
        if (payload != null) {
            payload.release();
            payload = null;
        }
        // 重置字段
        connectionId = 0;
        frameType = 0;
        sequence = 0;
        total = 0;
        dataId = 0;
        frameTotalLength = 0; // 新增：重置帧总长度
        remoteAddress = null; // 新增：重置远端地址

        // 归还对象
        handle.recycle(this);
    }

    // 防御性校验：帧是否有效
    public boolean isValid() {
        // 1. 基础字段非负/合法
        boolean basicValid = connectionId > 0 && dataId > 0 && total > 0 && sequence >= 0;
        // 2. frameTotalLength必须≥固定头部长度（否则payload长度为负）
        boolean lengthValid = frameTotalLength >= FIXED_HEADER_LENGTH;
        // 3. payload校验：非空且长度匹配（frameTotalLength - 固定头部）
        boolean payloadValid = payload != null && payload.readableBytes() == (frameTotalLength - FIXED_HEADER_LENGTH);

        return basicValid && lengthValid && payloadValid;
    }


}
