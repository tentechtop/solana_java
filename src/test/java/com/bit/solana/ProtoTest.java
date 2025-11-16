package com.bit.solana;

import com.bit.solana.proto.block.Structure;
import com.bit.solana.structure.block.BlockHeader;
import com.google.protobuf.ByteString;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

@Slf4j
public class ProtoTest {
    public static void main(String[] args) {
        try {
            // 1. 创建ProtoBlockHeader对象
            Structure.ProtoBlockHeader header = Structure.ProtoBlockHeader.newBuilder()
                    .setPreviousBlockHash(ByteString.copyFromUtf8("PreviousBlockHash123"))
                    .setStateRootHash(ByteString.copyFromUtf8("StateRootHash456"))
                    .build();

            // 2. 创建包含header的ProtoBlock对象
            Structure.ProtoBlock block = Structure.ProtoBlock.newBuilder()
                    .setHeader(header)
                    .build();

            // 3. 序列化ProtoBlock对象为字节数组（记录时间）
            byte[] serializedData;
            long protoSerializeStart = System.nanoTime(); // 开始时间（纳秒）
            try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
                block.writeTo(outputStream);
                serializedData = outputStream.toByteArray();
            }
            long protoSerializeEnd = System.nanoTime(); // 结束时间（纳秒）
            System.out.println("Proto序列化成功，字节长度: " + serializedData.length
                    + "，耗时: " + (protoSerializeEnd - protoSerializeStart)/ 1_000_000.0 + "ns");

            // 4. 反序列化字节数组为ProtoBlock对象（记录时间）
            Structure.ProtoBlock deserializedBlock;
            long protoDeserializeStart = System.nanoTime();
            try (ByteArrayInputStream inputStream = new ByteArrayInputStream(serializedData)) {
                deserializedBlock = Structure.ProtoBlock.parseFrom(inputStream);
            }
            long protoDeserializeEnd = System.nanoTime();
            System.out.println("Proto反序列化成功，耗时: " + (protoDeserializeEnd - protoDeserializeStart)/ 1_000_000.0 + "ns");

            // 5. 验证反序列化结果
            Structure.ProtoBlockHeader deserializedHeader = deserializedBlock.getHeader();
            System.out.println("原始previousBlockHash: PreviousBlockHash123");
            System.out.println("反序列化previousBlockHash: " + deserializedHeader.getPreviousBlockHash().toStringUtf8());
            System.out.println("原始stateRootHash: StateRootHash456");
            System.out.println("反序列化stateRootHash: " + deserializedHeader.getStateRootHash().toStringUtf8());


            // BlockHeader的序列化和反序列化时间记录
            BlockHeader blockHeader = new BlockHeader();
            blockHeader.setBlockTime(1234567890L);

            // 序列化BlockHeader
            long blockSerializeStart = System.nanoTime();
            byte[] serialize = blockHeader.serialize();
            long blockSerializeEnd = System.nanoTime();
            log.info("BlockHeader序列化字节长度: {}, 耗时: {}ns", serialize.length,
                    (blockSerializeEnd - blockSerializeStart)/ 1_000_000.0);

            // 反序列化BlockHeader
            long blockDeserializeStart = System.nanoTime();
            BlockHeader deserialize = BlockHeader.deserialize(serialize);
            long blockDeserializeEnd = System.nanoTime();
            log.info("BlockHeader反序列化成功，耗时: {}ns",
                    (blockDeserializeEnd - blockDeserializeStart)/ 1_000_000.0);
            log.info("反序列化结果: {}", deserialize);

        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}