package com.bit.solana.p2p.quic;

import com.bit.solana.p2p.protocol.ProtocolEnum;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Getter
@Slf4j
public enum QuicFrameEnum {
    DATA_FRAME((byte)1, "数据帧"),
    DATA_ACK_FRAME((byte)2, "数据ACK帧"),

    PING_FRAME((byte)3, "ping帧"),
    PONG_FRAME((byte)4, "pong帧"),

    CONNECT_REQUEST_FRAME((byte)5, "连接请求帧"),
    CONNECT_RESPONSE_FRAME((byte)6, "连接响应帧"),



    OFF_FRAME((byte)6, "下线帧"),//通知类帧 无回复




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
