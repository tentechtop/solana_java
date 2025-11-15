package com.bit.solana;

import com.bit.solana.proto.block.Structure;
import com.google.protobuf.ByteString;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

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

            // 3. 序列化ProtoBlock对象为字节数组
            byte[] serializedData;
            try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
                block.writeTo(outputStream);
                serializedData = outputStream.toByteArray();
                System.out.println("序列化成功，字节长度: " + serializedData.length);
            }

            // 4. 反序列化字节数组为ProtoBlock对象
            Structure.ProtoBlock deserializedBlock;
            try (ByteArrayInputStream inputStream = new ByteArrayInputStream(serializedData)) {
                deserializedBlock = Structure.ProtoBlock.parseFrom(inputStream);
                System.out.println("反序列化成功");
            }

            // 5. 验证反序列化结果
            Structure.ProtoBlockHeader deserializedHeader = deserializedBlock.getHeader();
            System.out.println("原始previousBlockHash: PreviousBlockHash123");
            System.out.println("反序列化previousBlockHash: " + deserializedHeader.getPreviousBlockHash().toStringUtf8());
            System.out.println("原始stateRootHash: StateRootHash456");
            System.out.println("反序列化stateRootHash: " + deserializedHeader.getStateRootHash().toStringUtf8());

        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}