package com.bit.solana.p2p.impl;


import com.bit.solana.p2p.quic.QuicConnection;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.concurrent.*;


import static com.bit.solana.util.ByteUtils.bytesToHex;

/**
 * 随用随建是其最优使用方式
 */

@Data
@Slf4j
public class QuicNodeWrapper {
    private byte[] nodeId; // 节点ID 公钥的base58编码
    private QuicConnection quicConnection;
}
