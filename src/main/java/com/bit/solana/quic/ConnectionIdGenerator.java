package com.bit.solana.quic;

import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * 连接ID生成器
 */
public class ConnectionIdGenerator {
    /**
     * 基于四元组生成连接ID（源IP+源端口+目的IP+目的端口）
     * murmur3_128()生成128位哈希，取低64位并屏蔽符号位，确保为正数
     */
    public static long generate(InetSocketAddress local, InetSocketAddress remote) {
        // 拼接四元组字符串（源IP:源端口-目的IP:目的端口）
        String quad = local.getAddress().getHostAddress() + ":" + local.getPort() +
                "-" + remote.getAddress().getHostAddress() + ":" + remote.getPort();

        HashCode hashCode = Hashing.murmur3_128().hashString(quad, StandardCharsets.UTF_8);
        long rawLong = hashCode.asLong();
        // 屏蔽符号位（第63位），保留低63位，确保结果为正数
        long connectionId = rawLong & Long.MAX_VALUE;
        return connectionId;
    }
}