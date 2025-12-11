package com.bit.solana.quic;

import com.bit.solana.p2p.protocol.ProtocolEnum;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Getter
@Slf4j
public enum QuicFrameEnum {
    DATA_FRAME((byte)0x01, "数据帧"),
    ACK_FRAME((byte)0x02, "ACK帧"),
    BATCH_ACK_FRAME((byte)0x08, "批量ACK帧"),

    PING_FRAME((byte)0x03, "ping帧"),
    PONG_FRAME((byte)0x04, "pong帧"),


    OFF_FRAME((byte)0x05, "下线帧"),
    ONLINE_FRAME((byte)0x06, "上线帧"),


    IMMEDIATE_REQUEST_FRAME((byte)0x07, "重传请求帧"),//立即请求一个数据帧




    ;

    /**
     * 立即索取帧 即接收方收到 123 5 在收到5的时候发现自己缺少4 4可能在途 即刻建立20ms延时任务 如果20ms 4到达就核销掉
     */



    private final byte code;
    private final String name;

    QuicFrameEnum(byte code, String name) {
        this.code = code;
        this.name = name;
    }


    public static QuicFrameEnum fromCode(int code) {
        //code转byte
        byte b = (byte)code;
        for (QuicFrameEnum e : values()) {
            if (e.getCode() == b) {
                return e;
            }
        }
        throw new IllegalArgumentException("无效的帧code：" + code);
    }

    public static QuicFrameEnum fromCode(byte code) {
        //code转byte
        for (QuicFrameEnum e : values()) {
            if (e.getCode() == code) {
                return e;
            }
        }
        throw new IllegalArgumentException("无效的帧code：" + code);
    }



}
