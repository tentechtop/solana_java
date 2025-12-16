package com.bit.solana.quic;

import io.netty.buffer.ByteBuf;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import static com.bit.solana.quic.QuicConstants.ALLOCATOR;


@Slf4j
@Data
public class QuicData {
    private long connectionId;//连接ID
    private long dataId;//数据ID
    private int total;//数据帧总数
    private QuicFrame[] frameArray;//帧数据按照序列号存入
    private boolean isComplete;//数据是否完整


    /**
     * 通过完整二进制数据构建QuicData对象
     * （将完整数据分片为多个QuicFrame，封装为QuicData）
     *
     * @param connectionId  连接ID
     * @param dataId        数据ID
     * @param frameType     帧类型
     * @param fullData      完整的二进制数据
     * @param maxFrameSize  单个帧的最大载荷长度（不含固定头部）
     * @return 构建完成的QuicData对象
     */
    public static QuicData buildFromFullData(long connectionId, long dataId, byte frameType,
                                             ByteBuf fullData, int maxFrameSize) {
        // 前置校验
        if (fullData == null || !fullData.isReadable()) {
            throw new IllegalArgumentException("完整数据不能为空或不可读");
        }
        if (maxFrameSize < 1) {
            throw new IllegalArgumentException("单帧最大载荷长度必须≥1，实际：" + maxFrameSize);
        }

        QuicData quicData = new QuicData();
        quicData.connectionId = connectionId;
        quicData.dataId = dataId;

        // 1. 计算分片总数和每帧长度
        int fullDataLength = fullData.readableBytes();
        // 单个帧的总长度 = 固定头部 + 最大载荷长度
        int singleFrameTotalLength = QuicFrame.FIXED_HEADER_LENGTH + maxFrameSize;
        // 计算需要的分片数（向上取整）
        int totalFrames = (fullDataLength + maxFrameSize - 1) / maxFrameSize;
        quicData.total = totalFrames;
        quicData.frameArray = new QuicFrame[totalFrames];

        // 2. 分片构建QuicFrame
        fullData.markReaderIndex(); // 标记原始读取位置，避免修改外部缓冲区
        try {
            for (int sequence = 0; sequence < totalFrames; sequence++) {
                // 创建帧实例
                QuicFrame frame = QuicFrame.acquire();//等待和QuicData一起释放
                frame.setConnectionId(connectionId);
                frame.setDataId(dataId);
                frame.setTotal(totalFrames);
                frame.setFrameType(frameType);
                frame.setSequence(sequence);

                // 计算当前帧的载荷长度
                int remainingBytes = fullData.readableBytes();
                int currentPayloadLength = Math.min(remainingBytes, maxFrameSize);
                // 设置帧总长度（固定头部 + 当前载荷长度）
                frame.setFrameTotalLength(QuicFrame.FIXED_HEADER_LENGTH + currentPayloadLength);

                // 复制载荷数据（避免引用外部缓冲区）
                ByteBuf payload = ALLOCATOR.buffer(currentPayloadLength);
                fullData.readBytes(payload, currentPayloadLength);
                frame.setPayload(payload);

                // 将帧存入数组
                quicData.frameArray[sequence] = frame;
            }
            quicData.setComplete(true);
        } catch (Exception e) {
            log.error("构建QuicData失败 connectionId={}, dataId={}", connectionId, dataId, e);
            // 异常时释放已创建的帧
            for (QuicFrame frame : quicData.frameArray) {
                if (frame != null) {
                    frame.release();
                }
            }
            throw new RuntimeException("构建QuicData失败", e);
        } finally {
            fullData.resetReaderIndex(); // 恢复外部缓冲区读取位置
        }

        return quicData;
    }

    /**
     * 根据序列号获取帧数据
     *
     * @param sequence 序列号（0 ~ total-1）
     * @return 对应的QuicFrame（null表示不存在）
     */
    public QuicFrame getFrameBySequence(int sequence) {
        // 前置校验
        if (sequence < 0 || sequence >= total) {
            log.warn("序列号非法 connectionId={}, dataId={}, sequence={}, total={}",
                    connectionId, dataId, sequence, total);
            return null;
        }
        if (frameArray == null || frameArray.length != total) {
            log.warn("帧数组异常 connectionId={}, dataId={}, arrayLength={}, total={}",
                    connectionId, dataId, frameArray == null ? 0 : frameArray.length, total);
            return null;
        }
        return frameArray[sequence];
    }


    /**
     * 获取帧组合后的完整二进制数据
     * （仅当数据完整时返回有效数据，否则返回null）
     *
     * @return 组合后的完整ByteBuf（使用后需手动release）
     */
    public ByteBuf getCombinedFullData() {
        // 前置校验：数据必须完整
        if (!isComplete) {
            log.warn("数据未完整，无法组合 connectionId={}, dataId={}", connectionId, dataId);
            return null;
        }
        if (frameArray == null || frameArray.length != total || total == 0) {
            log.warn("帧数组异常，无法组合 connectionId={}, dataId={}, total={}",
                    connectionId, dataId, total);
            return null;
        }

        // 1. 计算总数据长度
        int totalDataLength = 0;
        for (QuicFrame frame : frameArray) {
            if (frame == null || frame.getPayload() == null || !frame.getPayload().isReadable()) {
                log.warn("帧数据为空 connectionId={}, dataId={}, sequence={}",
                        connectionId, dataId, frame == null ? "null" : frame.getSequence());
                return null;
            }
            totalDataLength += frame.getPayload().readableBytes();
        }

        // 2. 分配缓冲区并组合数据
        ByteBuf fullData = ALLOCATOR.buffer(totalDataLength);
        try {
            for (QuicFrame frame : frameArray) {
                ByteBuf payload = frame.getPayload();
                payload.markReaderIndex(); // 标记载荷读取位置
                fullData.writeBytes(payload);
                payload.resetReaderIndex(); // 恢复载荷读取位置
            }
            return fullData;
        } catch (Exception e) {
            log.error("组合完整数据失败 connectionId={}, dataId={}", connectionId, dataId, e);
            fullData.release(); // 异常时释放缓冲区
            return null;
        }
    }

    /**
     * 释放QuicData关联的所有帧资源
     * （使用完QuicData后建议调用，避免内存泄漏）
     */
    public void release() {
        if (frameArray != null) {
            for (QuicFrame frame : frameArray) {
                if (frame != null) {
                    frame.release();
                }
            }
            frameArray = null;
        }
        isComplete = false;
        total = 0;
        dataId = 0;
        connectionId = 0;
    }



}
