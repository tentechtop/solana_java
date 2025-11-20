package com.bit.solana;

import com.bit.solana.common.BlockHash;
import com.bit.solana.p2p.peer.Peer;
import com.bit.solana.proto.Structure;
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
            blockHeader.setPreviousBlockHash(new BlockHash(null));
            blockHeader.setBlockTime(1L);

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



// 构建Peer对象
            Peer peer = Peer.builder()
                    .id("7Np41oeYqPefeNQEHSv1UDhYrehxin3NStELsSKCT4K2".getBytes())
                    .address("192.168.1.100")
                    .port(8000)
                    .multiaddr("/ip4/192.168.1.100/tcp/8000/p2p/7Np41oeYqPefeNQEHSv1UDhYrehxin3NStELsSKCT4K2")
                    .protocolVersion("solana-p2p/1.14.19")
                    .nodeType(1)
                    .isOnline(true)
                    .latestSlot(198765432L)
                    .isValidator(true)
                    .stakeAmount(10000.5)
                    .softwareVersion(11419)
                    .lastSeen(System.currentTimeMillis())
                    .build();

// 序列化
            byte[] serialized = peer.serialize();

// 反序列化
            Peer deserializedPeer = Peer.deserialize(serialized);

// 验证一致性
            System.out.println(peer.getAddress().equals(deserializedPeer.getAddress())); // true
            System.out.println(peer.getPort() == deserializedPeer.getPort()); // true

        } catch (IOException e) {
            e.printStackTrace();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}