package com.bit.solana.p2p.protocol;

import com.bit.solana.util.ByteUtils;
import lombok.Data;
import lombok.ToString;
import org.bitcoinj.core.Base58;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

import static com.bit.solana.util.ByteUtils.bytesToHex;

/**
 * Solana P2P网络消息协议实体
 * 核心字段遵循网络字节序（大端序），适配跨节点/跨语言传输
 * 字段规范：
 * - senderId：32字节Solana公钥（Base58编码后为字符串）
 * - messageId：16字节UUID V7（分布式唯一消息ID）
 * - requestId：16字节UUID V7（请求响应关联ID，全零数组代表非请求/响应消息）
 * - reqResFlag：请求/响应标识（0=请求，1=响应，仅当requestId非全零时有效）
 * - type：消息类型（如PING=1, PONG=2, TRANSACTION=3等）
 * - length：data字段长度（protobuf序列化后，避免NPE）
 * - version：协议版本（默认1，最小1，最大Short.MAX_VALUE）
 * - data：protobuf序列化后的业务数据
 */
@Data
@ToString(exclude = "data") // toString排除大体积data字段，避免日志冗余
public class P2PMessage {
    // ===================== 常量定义（公开，方便外部使用） =====================
    /** 最小协议版本号（从1开始，0代表未初始化） */
    public static final short MIN_VERSION = 1;
    /** 最大协议版本号（Short最大值，可根据业务扩展） */
    public static final short MAX_VERSION = Short.MAX_VALUE;
    /** Solana公钥固定长度（字节） */
    public static final int SOLANA_PUBKEY_LENGTH = 32;
    /** UUID V7固定长度（字节） */
    public static final int UUID_V7_LENGTH = 16;
    /** 请求标识（reqResFlag=0） */
    public static final byte REQ_FLAG = 0x00;
    /** 响应标识（reqResFlag=1） */
    public static final byte RES_FLAG = 0x01;

    // ===================== 核心字段 =====================
    private byte[] senderId;       // 32字节Solana公钥（消息发送者ID）
    private byte[] messageId;      // 16字节UUID V7（分布式消息唯一ID）
    private byte[] requestId;      // 16字节UUID V7（请求响应关联ID，全零=非请求/响应）
    private byte reqResFlag;       // 请求/响应标识：0=请求，1=响应（仅requestId非全零时有效）
    private int type;              // 消息类型（转发至对应处理器）
    private int length;            // data字段长度（protobuf序列化后）
    private short version = 1;     // 协议版本（默认1，防篡改）
    private byte[] data;           // 业务数据（protobuf序列化，JVM数组长度为int）

    // ===================== 核心逻辑方法（修复+优化） =====================
    /**
     * 判断是否为请求/响应类消息（如ping/pong）
     * 核心逻辑：requestId非空且不是全零数组
     */
    public boolean isReqRes() {
        return requestId != null && requestId.length == UUID_V7_LENGTH && !isAllZero(requestId);
    }

    /**
     * 判断是否为请求消息（如ping）
     * 前提：requestId非全零 + reqResFlag=0
     */
    public boolean isRequest() {
        return isReqRes() && reqResFlag == REQ_FLAG;
    }

    /**
     * 判断是否为响应消息（如pong）
     * 前提：requestId非全零 + reqResFlag=1
     */
    public boolean isResponse() {
        return isReqRes() && reqResFlag == RES_FLAG;
    }

    // ===================== 字段设置方法（增强校验） =====================
    /**
     * 设置发送者ID（Solana公钥），强制校验32字节长度
     */
    public void setSenderId(byte[] senderId) {
        if (senderId != null && senderId.length != SOLANA_PUBKEY_LENGTH) {
            throw new IllegalArgumentException(
                    String.format("发送者ID必须为%d字节（Solana公钥），当前长度：%d",
                            SOLANA_PUBKEY_LENGTH, senderId.length)
            );
        }
        this.senderId = senderId;
    }

    /**
     * 设置消息ID（UUID V7），强制校验16字节长度
     */
    public void setMessageId(byte[] messageId) {
        if (messageId != null && messageId.length != UUID_V7_LENGTH) {
            throw new IllegalArgumentException(
                    String.format("消息ID必须为%d字节（UUID V7），当前长度：%d",
                            UUID_V7_LENGTH, messageId.length)
            );
        }
        this.messageId = messageId;
    }

    /**
     * 设置请求响应ID（UUID V7），强制校验16字节长度（全零=非请求/响应）
     */
    public void setRequestId(byte[] requestId) {
        if (requestId != null && requestId.length != UUID_V7_LENGTH) {
            throw new IllegalArgumentException(
                    String.format("请求响应ID必须为%d字节（UUID V7），当前长度：%d",
                            UUID_V7_LENGTH, requestId.length)
            );
        }
        this.requestId = requestId;
    }

    /**
     * 设置请求/响应标识，仅允许0或1
     */
    public void setReqResFlag(byte reqResFlag) {
        if (reqResFlag != REQ_FLAG && reqResFlag != RES_FLAG) {
            throw new IllegalArgumentException(
                    String.format("请求响应标识仅允许%d（请求）或%d（响应），当前值：%d",
                            REQ_FLAG, RES_FLAG, reqResFlag)
            );
        }
        this.reqResFlag = reqResFlag;
    }

    /**
     * 设置业务数据，自动同步length字段（防御null）
     */
    public void setData(byte[] data) {
        this.data = data;
        this.length = data == null ? 0 : data.length;
    }

    /**
     * 设置协议版本号（int重载，防超出short范围）
     */
    public void setVersion(int version) {
        if (version < MIN_VERSION || version > MAX_VERSION) {
            throw new IllegalArgumentException(
                    String.format("协议版本号必须在[%d, %d]范围内，当前值：%d",
                            MIN_VERSION, MAX_VERSION, version)
            );
        }
        this.version = (short) version;
    }

    /**
     * 设置协议版本号（short重载）
     */
    public void setVersion(short version) {
        if (version < MIN_VERSION || version > MAX_VERSION) {
            throw new IllegalArgumentException(
                    String.format("协议版本号必须在[%d, %d]范围内，当前值：%d",
                            MIN_VERSION, MAX_VERSION, version)
            );
        }
        this.version = version;
    }

    // ===================== 工具方法（适配Solana+调试） =====================
    /**
     * 获取senderId的Base58字符串（Solana生态标准格式）
     */
    public String getSenderIdBase58() {
        return isValidSenderId() ? Base58.encode(senderId) : null;
    }

    /**
     * 获取messageId的16进制字符串（方便日志/调试）
     */
    public String getMessageIdHex() {
        return messageId != null ? bytesToHex(messageId) : null;
    }

    /**
     * 获取requestId的16进制字符串（方便日志/调试）
     */
    public String getRequestIdHex() {
        return requestId != null ? bytesToHex(requestId) : null;
    }

    /**
     * 校验senderId是否为合法Solana公钥（32字节非空）
     */
    public boolean isValidSenderId() {
        return senderId != null && senderId.length == SOLANA_PUBKEY_LENGTH;
    }

    /**
     * 校验messageId是否为合法UUID V7（16字节非空）
     */
    public boolean isValidMessageId() {
        return messageId != null && messageId.length == UUID_V7_LENGTH;
    }

    /**
     * 校验length与data长度是否一致（防篡改/序列化错误）
     */
    public boolean isLengthConsistent() {
        return (data == null && length == 0) || (data != null && data.length == length);
    }

    /**
     * 校验版本号是否合法
     */
    public boolean isValidVersion() {
        return version >= MIN_VERSION;
    }

    // ===================== 重写toString =====================
    @Override
    public String toString() {
        return "P2PMessage{" +
                "senderId=" + getSenderIdBase58() +
                ", messageId=" + getMessageIdHex() +
                ", requestId=" + getRequestIdHex() +
                ", reqResFlag=" + reqResFlag +
                ", type=" + type +
                ", length=" + length +
                ", version=" + version +
                '}';
    }

    /**
     * 判断字节数组是否全零（用于requestId判定）
     */
    private static boolean isAllZero(byte[] bytes) {
        if (bytes == null) return true;
        for (byte b : bytes) {
            if (b != 0) return false;
        }
        return true;
    }





}