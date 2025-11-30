package com.bit.solana.p2p.quic;

import io.netty.buffer.ByteBuf;
import io.netty.util.Recycler;
import lombok.Data;

import java.net.InetSocketAddress;

/**
 * 帧结构设计
 * INITIAL Frame 初始握手包，携带版本 / Connection ID  Version、DCID（目标连接 ID）、SCID（源连接 ID）、Token、长度
 * STREAM Frame  流数据传输 Stream ID、Fin（流结束）、Offset（流内偏移）、Length、Payload
 * ACK Frame     确认收到的包  Largest ACKed、ACK Delay、ACK Range（批量确认范围）、ECE（拥塞提示）
 * CONNECTION_CLOSE	 关闭连接  Error Code、Reason Phrase、Length
 * RETRY Frame 重试握手 Retry Token、DCID、SCID
 * PADDING Frame 填充包（避免长度泄露）
 */
@Data
public class QuicFrame {
    // 对象池 定义 Recycler 池，指定“新对象创建规则”
    private static final Recycler<QuicFrame> RECYCLER = new Recycler<QuicFrame>() {
        @Override
        protected QuicFrame newObject(Handle<QuicFrame> handle) {
            //创建新对象时，把 Netty 分配的 Handle 绑定到对象的 handle 字段
            return new QuicFrame(handle);
        }
    };


    /**
     * 这个 handle 是 Netty 内置对象池框架 Recycler 的核心句柄，
     * 是实现 ReliableUdpFrame 高效池化、避免频繁 GC 的关键
     * 是什么、为什么需要、怎么用
     * Handle 是 Netty Recycler 类的内部接口（Recycler.Handle<T>），你可以把它理解为：每个池化对象的「专属回收入口」 ——
     * 它是对象实例和 Recycler 池之间的唯一关联，负责把用完的对象「归还」到对象池，而不是让对象被 GC 回收
     * 你的代码中 ReliableUdpFrame 是高频创建 / 销毁的核心帧对象（每个 UDP 包都会封装成这个帧），如果每次用完都 GC，会导致：
     * 频繁的 Minor GC（新生代垃圾回收），甚至触发 Full GC，影响性能；
     * 内存碎片增多，降低内存利用率。
     * 而 handle 的核心价值就是：
     * 让 ReliableUdpFrame 实例用完后不被 GC，而是通过 handle.recycle(this) 归池复用；
     * final 修饰是为了保证句柄不被篡改（避免回收时指向错误的池），确保对象能稳定归池。
     *
     */
    private final Recycler.Handle<QuicFrame> handle;
    private long connectionId; // 连接ID（四元组哈希）
    private int streamId; // 流ID
    private byte frameType; // 帧类型
    private long sequence; // 序列号
    private long ackSequence; // ACK确认号
    private int windowSize; // 窗口大小
    private byte priority; // 流优先级（0-7，7最高）
    private int fecGroupId; // FEC组ID
    private int fecIndex; // FEC组内索引
    private long qpsLimit; // QPS上限（0表示无限制）
    private boolean isFileRegion; // 是否为零拷贝文件传输
    private long fileOffset; // 文件偏移量
    private long fileLength; // 文件长度
    private String fileName; // 文件名（可选）
    private ByteBuf payload; // 有效载荷

    // 传输元数据
    private InetSocketAddress remoteAddress;
    private long sendTime; // 发送时间（用于RTT计算）
    private int retransmitCount; // 重传次数


    private QuicFrame(Recycler.Handle<QuicFrame> handle) {
        this.handle = handle;
    }

    // 从对象池获取实例
    public static QuicFrame acquire() {
        return RECYCLER.get();
    }

    // 归还到对象池
    public void release() {
        // 释放ByteBuf
        if (payload != null) {
            payload.release();
            payload = null;
        }
        // 重置字段
        connectionId = 0;
        streamId = 0;
        frameType = 0;
        sequence = 0;
        ackSequence = 0;
        windowSize = 0;
        priority = 0;
        fecGroupId = 0;
        fecIndex = 0;
        qpsLimit = 0;
        isFileRegion = false;
        fileOffset = 0;
        fileLength = 0;
        fileName = null;
        remoteAddress = null;
        sendTime = 0;
        retransmitCount = 0;
        // 归还对象
        handle.recycle(this);
    }

    // ========== 序列化/反序列化 ==========
    public void encode(ByteBuf buf) {
        // 帧头（固定64字节）
        buf.writeLong(connectionId);
        buf.writeInt(streamId);
        buf.writeByte(frameType);
        buf.writeLong(sequence);
        buf.writeLong(ackSequence);
        buf.writeInt(windowSize);
        buf.writeByte(priority);
        buf.writeInt(fecGroupId);
        buf.writeInt(fecIndex);
        buf.writeLong(qpsLimit);
        buf.writeBoolean(isFileRegion);
        buf.writeLong(fileOffset);
        buf.writeLong(fileLength);
        int fileNameLen = fileName == null ? 0 : fileName.getBytes().length;
        buf.writeInt(fileNameLen);
        if (fileNameLen > 0) {
            buf.writeBytes(fileName.getBytes());
        }
        // 填充到64字节
        int padding = 64 - buf.writerIndex();
        if (padding > 0) {
            buf.writeZero(padding);
        }
        // 有效载荷
        if (payload != null) {
            buf.writeBytes(payload);
        }
    }

    public static QuicFrame decode(ByteBuf buf, InetSocketAddress remoteAddress) {
        QuicFrame frame = acquire();
        frame.remoteAddress = remoteAddress;
        // 解析帧头
        frame.connectionId = buf.readLong();
        frame.streamId = buf.readInt();
        frame.frameType = buf.readByte();
        frame.sequence = buf.readLong();
        frame.ackSequence = buf.readLong();
        frame.windowSize = buf.readInt();
        frame.priority = buf.readByte();
        frame.fecGroupId = buf.readInt();
        frame.fecIndex = buf.readInt();
        frame.qpsLimit = buf.readLong();
        frame.isFileRegion = buf.readBoolean();
        frame.fileOffset = buf.readLong();
        frame.fileLength = buf.readLong();
        int fileNameLen = buf.readInt();
        if (fileNameLen > 0) {
            byte[] fileNameBytes = new byte[fileNameLen];
            buf.readBytes(fileNameBytes);
            frame.fileName = new String(fileNameBytes);
        }
        // 跳过填充
        int padding = 64 - buf.readerIndex();
        if (padding > 0) {
            buf.skipBytes(padding);
        }
        // 解析有效载荷
        if (buf.readableBytes() > 0) {
            frame.payload = buf.readRetainedSlice(buf.readableBytes());
        }
        return frame;
    }

    public void incrementRetransmitCount() { this.retransmitCount++; }

}
