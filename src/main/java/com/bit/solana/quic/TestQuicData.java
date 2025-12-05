package com.bit.solana.quic;

import com.bit.solana.util.SnowflakeIdGenerator;
import com.bit.solana.util.UUIDv7Generator;
import io.netty.buffer.ByteBuf;

public class TestQuicData {
    public static void main(String[] args) {








        SnowflakeIdGenerator generator1 = new SnowflakeIdGenerator();

        // 1. 模拟生成首个分片帧（total=3，dataId=UUIDV7，connectionId=123456）
        QuicFrame firstFrame = QuicFrame.acquire();
        firstFrame.setConnectionId(123456L);
        firstFrame.setDataId(generator1.nextId()); // 模拟UUIDV7
        firstFrame.setTotal(3); // 总分片数=3
        firstFrame.setFrameType(QuicFrameEnum.DATA_FRAME.getCode());
        firstFrame.setSequence(0); // 首个分片序列号=0
        firstFrame.setPayload(QuicConstants.ALLOCATOR.buffer().writeBytes("分片0".getBytes()));

        // 2. 初始化QuicData
        QuicData quicData = new QuicData(firstFrame);

        // 3. 添加分片1
        QuicFrame frame1 = QuicFrame.acquire();
        frame1.setConnectionId(123456L);
        frame1.setDataId(firstFrame.getDataId());
        frame1.setTotal(3);
        frame1.setFrameType(QuicFrameEnum.DATA_FRAME.getCode());
        frame1.setSequence(1);
        frame1.setPayload(QuicConstants.ALLOCATOR.buffer().writeBytes("分片1".getBytes()));
        quicData.addQuicFrame(frame1);

        // 4. 添加分片2（添加后已完整）
        QuicFrame frame2 = QuicFrame.acquire();
        frame2.setConnectionId(123456L);
        frame2.setDataId(firstFrame.getDataId());
        frame2.setTotal(3);
        frame2.setFrameType(QuicFrameEnum.DATA_FRAME.getCode());
        frame2.setSequence(2);
        frame2.setPayload(QuicConstants.ALLOCATOR.buffer().writeBytes("分片2".getBytes()));
        quicData.addQuicFrame(frame2);

        // 5. 拼接完整数据
        ByteBuf completeData = quicData.getData();
        if (completeData != null) {
            try {
                byte[] result = new byte[completeData.readableBytes()];
                completeData.readBytes(result);
                System.out.println("完整数据：" + new String(result)); // 输出：分片0分片1分片2
            } finally {
                completeData.release(); // 必须释放
            }
        }

        // 6. 释放所有资源
        quicData.releaseAll();
    }
}