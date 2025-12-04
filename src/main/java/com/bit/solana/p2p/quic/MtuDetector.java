package com.bit.solana.p2p.quic;

import lombok.Data;
import org.springframework.stereotype.Component;

/**
 * MTU探测器（路径MTU发现）
 */
@Data
public class MtuDetector {
    private final QuicConnection connection;
    private int currentMtu = QuicConstants.DEFAULT_MTU;
    private int probeMtu = QuicConstants.DEFAULT_MTU;


    public MtuDetector(QuicConnection connection) {
        this.connection = connection;
        // 启动MTU探测
        startProbe();
    }

    private void startProbe() {

    }


    private void sendMtuProbeFrame(int mtu) {
        QuicFrame frame = QuicFrame.acquire();
        frame.setConnectionId(connection.getConnectionId());
        frame.setStreamId(-1);
        frame.setFrameType(QuicConstants.FRAME_TYPE_MTU_DETECT);
        frame.setPayload(QuicConstants.ALLOCATOR.buffer(mtu - 64).writeZero(mtu - 64));
        frame.setRemoteAddress(connection.getRemoteAddress());
        connection.sendFrame(frame);
    }


    public void handleMtuDetectFrame(QuicFrame frame) {
        int probeMtu = frame.getPayload().readableBytes() + 64;
        // 收到响应，更新当前MTU
        currentMtu = probeMtu;
        connection.getMetrics().updateCurrentMtu(currentMtu);
    }
}
