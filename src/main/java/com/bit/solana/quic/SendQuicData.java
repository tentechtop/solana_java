package com.bit.solana.quic;

import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import io.netty.util.Timer;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Data
public class SendQuicData extends QuicData {
    // ACK确认集合：记录B已确认的序列号
    private final Set<Integer> ackedSequences = Collections.newSetFromMap(new ConcurrentHashMap<>());


    private Timeout globalTimeout;
    // 单帧重传定时器（序列号→定时器）
    private final ConcurrentHashMap<Integer, Timeout> retransmitTimers = new ConcurrentHashMap<>();
    // 单帧重传次数（序列号→次数）
    private final ConcurrentHashMap<Integer, AtomicInteger> retransmitCounts = new ConcurrentHashMap<>();
    // 传输完成回调
    private Runnable successCallback;
    // 传输失败回调
    private Runnable failCallback;


    //全局超时定时器 300ms 内 B 未收全则判定失败

    //帧重传定时器 针对未收到 ACK 的帧重传  比如 50ms / 次，最多 6 次




    //当收到ACK帧时 加入ACK 确认集合  取消对应帧的重传定时器 检查是否收全 ACK
    //重传触发：如果某帧的重传定时器超时（50ms）且未收到 ACK，重新发送该帧，重传次数 + 1；
    //重传次数超限：单帧重传 6 次仍未收到 ACK，判定该帧传输失败，触发全局超时；


}
