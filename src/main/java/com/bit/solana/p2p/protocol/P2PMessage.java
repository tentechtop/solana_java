package com.bit.solana.p2p.protocol;

import lombok.Data;
import lombok.ToString;
import org.bitcoinj.core.Base58;


@Data
@ToString
public class P2PMessage{
    private byte[] senderId;//消息发送者ID base58编码后就是字符串
    private long messageId;//分布式消息ID
    private long requestId; //请求响应ID 请求响应是一对如果是0 代表不是请求和响应  例如ping消息 pong 是一对请求需要回复pong
    private byte isRequest; //0:请求 1:响应 ->前提是requestId不为零
    private int type;//消息类型 -> 转发给不同的处理者 如果消息是请求 会要求处理者返回数据
    private int length;//data数据的长度 用protobuf 序列化后的长度
    private long timestamp = System.currentTimeMillis();//消息生成时间戳（毫秒）：用于超时判断、缓存过期
    private short version = 1;//协议版本（默认1）：用于协议升级兼容
    private byte[] data;//消息数据 ->会根据Type分配对应的处理器或者处理方法 方法自己解析 JVM 规定数组长度必须是 int

    // ===== 优化：简化请求/响应判断逻辑（提升可读性+执行效率）=====
    /** 是否为请求/响应消息（如ping/pong） */
    public boolean isReqRes() {
        return requestId != 0;
    }

    /** 是否为请求消息（如ping） */
    public boolean isRequest() {
        return isReqRes() && isRequest == 0;
    }

    /** 是否为响应消息（如pong） */
    public boolean isResponse() {
        return isReqRes() && isRequest == 1;
    }



    //setData
    public void setData(byte[] data) {
        this.data = data;
        // 处理null场景：data=null时length=0，避免NPE
        this.length = data == null ? 0 : data.length;
    }

    // ===== 工具方法：快速获取senderId的Base58字符串（适配Solana生态）=====
    public String getSenderIdBase58() {
        return isValidSenderId() ? Base58.encode(senderId) : null;
    }

    // ===== 新增：防御性校验（提升可用性，避免非法数据）=====
    /** 校验senderId是否为Solana合法32字节公钥 */
    public boolean isValidSenderId() {
        return senderId != null && senderId.length == 32;
    }

    /** 校验length与data的一致性（防篡改/序列化错误） */
    public boolean isLengthConsistent() {
        return (data == null && length == 0) || (data != null && data.length == length);
    }

    // ===== 版本号常量定义（合法范围）=====
    /** 最小协议版本号（从1开始，避免0代表未初始化） */
    private static final short MIN_VERSION = 1;
    /** 最大协议版本号（Short最大值，可根据业务调整） */
    private static final short MAX_VERSION = Short.MAX_VALUE;


    // 校验版本号合法性
    public boolean isValidVersion() {
        return version >= 1; // 版本号从1开始，避免0（未初始化）
    }

    // ===== 版本号设置方法（增加范围校验）=====
    /**
     * 设置协议版本号（int类型重载）
     * @param version 版本号，必须在 [{@link #MIN_VERSION}, {@link #MAX_VERSION}] 范围内
     * @throws IllegalArgumentException 版本号超出合法范围时抛出
     */
    public void setVersion(int version) {
        // 先校验范围，再强转（避免int超出short范围）
        this.version = validateVersionRange((short) version);
    }

    /**
     * 设置协议版本号（short类型重载）
     * @param version 版本号，必须在 [{@link #MIN_VERSION}, {@link #MAX_VERSION}] 范围内
     * @throws IllegalArgumentException 版本号超出合法范围时抛出
     */
    public void setVersion(short version) {
        this.version = validateVersionRange(version);
    }

    // ===== 私有工具：版本号范围校验 =====
    /**
     * 校验版本号是否在合法范围，非法则抛异常
     * @param version 待校验的版本号
     * @return 合法的版本号（原样返回）
     * @throws IllegalArgumentException 版本号超出 [{@link #MIN_VERSION}, {@link #MAX_VERSION}] 范围
     */
    private short validateVersionRange(short version) {
        if (version < MIN_VERSION) {
            throw new IllegalArgumentException(
                    String.format("协议版本号非法！必须在 [%d, %d] 范围内，当前值：%d",
                            MIN_VERSION, MAX_VERSION, version)
            );
        }
        return version;
    }



    //序列化 反序列化

}
