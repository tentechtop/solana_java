package com.bit.solana.p2p.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.ByteOrder;
import java.util.UUID;

/**
 * 协议数据包编解码器
 * 数据包格式（请求）：
 * +----------------+----------------+----------------+----------------+
 * | 协议码(4字节)  | 请求ID(8字节)  | 参数长度(4字节) | 参数二进制     |
 * +----------------+----------------+----------------+----------------+
 * （无返回值请求的请求ID为全0）
 *
 * 响应格式：
 * +----------------+----------------+----------------+
 * | 请求ID(8字节)  | 响应长度(4字节) | 响应二进制     |
 * +----------------+----------------+----------------+
 */
public class ProtocolPacketCodec {
    // 网络字节序（大端）
    private static final ByteOrder BYTE_ORDER = ByteOrder.BIG_ENDIAN;

    // ===== 新增：数据包类型枚举 =====
    public enum PacketType {
        REQUEST((byte) 0),  // 请求包
        RESPONSE((byte) 1); // 响应包

        private final byte type;

        PacketType(byte type) {
            this.type = type;
        }

        public static PacketType getByType(byte type) {
            for (PacketType pt : values()) {
                if (pt.type == type) {
                    return pt;
                }
            }
            throw new IllegalArgumentException("未知数据包类型: " + type);
        }

        public byte getType() {
            return type;
        }
    }

    /**
     * 构建请求数据包
     * @param protocol 协议枚举
     * @param hasReturn 是否有返回值（决定是否生成请求ID）
     * @param params 请求参数（protobuf二进制）
     * @return 完整数据包
     */
    public static byte[] buildRequest(ProtocolEnum protocol, boolean hasReturn, byte[] params) {
        UUID requestId = hasReturn ? UUID.randomUUID() : new UUID(0, 0);
        ByteBuf buf = Unpooled.buffer();
        buf.order(BYTE_ORDER);

        // 1. 写入请求类型标识（1字节）
        buf.writeByte(PacketType.REQUEST.getType());
        // 2. 原有逻辑：协议码+请求ID+参数长度+参数
        buf.writeInt(protocol.getCode());
        buf.writeLong(requestId.getMostSignificantBits());
        buf.writeLong(requestId.getLeastSignificantBits());
        buf.writeInt(params.length);
        buf.writeBytes(params);

        byte[] result = new byte[buf.readableBytes()];
        buf.readBytes(result);
        buf.release();
        return result;
    }

    /**
     * 解析请求数据包
     * @param data 完整数据包
     * @return 解析结果
     */
    public static RequestPacket parseRequest(byte[] data) {
        ByteBuf buf = Unpooled.wrappedBuffer(data);
        buf.order(BYTE_ORDER);
        RequestPacket packet = new RequestPacket();

        // 1. 校验是否为请求包
        byte typeByte = buf.readByte();
        if (PacketType.getByType(typeByte) != PacketType.REQUEST) {
            throw new IllegalArgumentException("非请求包，无法解析为请求");
        }
        // 2. 原有逻辑：解析协议码、请求ID、参数
        int code = buf.readInt();
        packet.setProtocol(ProtocolEnum.getByCode(code));
        long most = buf.readLong();
        long least = buf.readLong();
        packet.setRequestId(new UUID(most, least));
        int paramLen = buf.readInt();
        byte[] params = new byte[paramLen];
        buf.readBytes(params);
        packet.setParams(params);
        packet.setHasReturn(!(most == 0 && least == 0));

        buf.release();
        return packet;
    }




    /**
     * 构建响应数据包
     * @param requestId 请求ID（关联请求）
     * @param response 响应数据（protobuf二进制）
     * @return 完整响应包
     */
    public static byte[] buildResponse(ProtocolEnum protocol, UUID requestId, byte[] response) {
        ByteBuf buf = Unpooled.buffer();
        buf.order(BYTE_ORDER);

        // 1. 写入响应类型标识（1字节）
        buf.writeByte(PacketType.RESPONSE.getType());
        // 2. 原有逻辑：协议码+请求ID+响应长度+响应数据
        buf.writeInt(protocol.getCode());
        buf.writeLong(requestId.getMostSignificantBits());
        buf.writeLong(requestId.getLeastSignificantBits());
        buf.writeInt(response.length);
        buf.writeBytes(response);

        byte[] result = new byte[buf.readableBytes()];
        buf.readBytes(result);
        buf.release();
        return result;
    }

    /**
     * 解析响应数据包
     * @param data 响应数据包
     * @return 解析结果
     */
    public static ResponsePacket parseResponse(byte[] data) {
        ByteBuf buf = Unpooled.wrappedBuffer(data);
        buf.order(BYTE_ORDER);
        ResponsePacket packet = new ResponsePacket();

        // 1. 校验是否为响应包
        byte typeByte = buf.readByte();
        if (PacketType.getByType(typeByte) != PacketType.RESPONSE) {
            throw new IllegalArgumentException("非响应包，无法解析为响应");
        }
        // 2. 原有逻辑：解析协议码、请求ID、响应数据
        int code = buf.readInt();
        packet.setProtocol(ProtocolEnum.getByCode(code));
        long most = buf.readLong();
        long least = buf.readLong();
        packet.setRequestId(new UUID(most, least));
        int respLen = buf.readInt();
        byte[] response = new byte[respLen];
        buf.readBytes(response);
        packet.setResponse(response);

        buf.release();
        return packet;
    }

    public static PacketType parsePacketType(byte[] data) {
        if (data.length < 1) {
            throw new IllegalArgumentException("数据包长度不足，无法解析类型");
        }
        ByteBuf buf = Unpooled.wrappedBuffer(data);
        try {
            byte typeByte = buf.readByte();
            return PacketType.getByType(typeByte);
        } finally {
            buf.release();
        }
    }


    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RequestPacket {
        private ProtocolEnum protocol;
        private UUID requestId;
        private byte[] params;
        private boolean hasReturn;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ResponsePacket {
        private ProtocolEnum protocol;
        private UUID requestId;
        private byte[] response;
    }
}