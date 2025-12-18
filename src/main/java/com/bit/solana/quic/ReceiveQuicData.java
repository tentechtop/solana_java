package com.bit.solana.quic;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import io.netty.util.Timer;
import io.netty.util.TimerTask;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import java.util.concurrent.atomic.AtomicInteger;

import static com.bit.solana.quic.QuicConnectionManager.*;
import static com.bit.solana.quic.QuicConstants.*;

@Slf4j
@Data
public class ReceiveQuicData extends QuicData {
    // 已经接收到的帧序列号
    private final Set<Integer> receivedSequences = Collections.newSetFromMap(new ConcurrentHashMap<>());



    // 全局定时器
    private volatile Timeout globalTimeout;
    // 单帧重传定时器（序列号→定时器）
    private final ConcurrentHashMap<Integer, Timeout> retransmitTimers = new ConcurrentHashMap<>();
    // 单帧重传次数（序列号→次数）
    private final ConcurrentHashMap<Integer, AtomicInteger> retransmitCounts = new ConcurrentHashMap<>();
    // 传输完成回调
    private Runnable successCallback;
    // 传输失败回调
    private Runnable failCallback;




    //当收到数据帧时
    public void handleFrame(QuicFrame quicFrame) {
        int sequence = quicFrame.getSequence();
        int total = quicFrame.getTotal();
        long dataId = quicFrame.getDataId();
        long connectionId = quicFrame.getConnectionId();
        InetSocketAddress remoteAddress = quicFrame.getRemoteAddress();
        // ========== 边界校验 ==========
        if (sequence < 0 || sequence >= total) {
            log.error("[非法帧] 连接ID:{} 数据ID:{} 序列号{}超出范围[0,{}]，拒绝处理",
                    connectionId, dataId, sequence, total - 1);
            //丢弃不处理
            return;
        }

        QuicFrame ackFrame = buildAckFrame(quicFrame);
        //异步赶紧回复ACK
        ByteBuf buf = QuicConstants.ALLOCATOR.buffer();
        ackFrame.encode(buf);
        DatagramPacket packet = new DatagramPacket(buf, ackFrame.getRemoteAddress());
        Global_Channel.writeAndFlush(packet).addListener(future -> {
            ackFrame.release();
        });

        // ========== 重复帧处理 ==========
        if (receivedSequences.contains(sequence)) {
            log.info("[重复帧] 连接ID:{} 数据ID:{} 序列号:{} 总帧数:{}，直接回复ACK",
                    connectionId, dataId, sequence, total);
            // 构建ACK帧（标记为已接收）
            // 取消该帧的重传定时器（如果存在）
            cancelRetransmitTimer(sequence);
            //释放帧
            quicFrame.release();
            return;
        }

        // ========== 非重复帧处理 ==========
        // 1. 初始化帧数组（首次接收时）
        if (getFrameArray() == null) {
            setFrameArray(new QuicFrame[total]);
            setTotal(total);
            setDataId(dataId);
            setConnectionId(connectionId);
            log.info("[初始化数据] 连接ID:{} 数据ID:{} 总帧数:{}", connectionId, dataId, total);
            // 启动全局超时定时器  动态全局超时 根据total动态计算
            startGlobalTimeoutTimer();
        }

        //克隆一份给到
        QuicFrame clone = quicFrame.clone();
        // 存储帧并更新计数
        getFrameArray()[sequence] = clone;
        receivedSequences.add(sequence);
        log.debug("[接收帧] 连接ID:{} 数据ID:{} 序列号:{} 已接收:{}/{}",
                connectionId, dataId, sequence, receivedSequences.size(), total);

        // ========== 完整性校验 & 重传处理 ==========
        if (receivedSequences.size() == total) {
            log.info("所有帧接收完成");
            // 所有帧接收完成
            handleDataComplete();
        }
        quicFrame.release();
    }




    private void startGlobalTimeoutTimer() {
        // 双重校验锁，防止重复创建
        synchronized (this) {
            if (globalTimeout == null && !isComplete()) {
                globalTimeout = GLOBAL_TIMER.newTimeout(new TimerTask() {
                    @Override
                    public void run(Timeout timeout) throws Exception {
                        if (timeout.isCancelled()) {
                            return;
                        }
                        log.error("[全局超时] 连接ID:{} 数据ID:{} 在{}ms内未接收完所有帧，接收失败",
                                getConnectionId(),  getDataId(), GLOBAL_TIMEOUT_MS);
                        handleDataFailure();
                    }
                }, GLOBAL_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
                log.info("[全局定时器启动] 连接ID:{} 数据ID:{} 超时时间:{}ms",
                        getConnectionId(), getDataId(), GLOBAL_TIMEOUT_MS);
            }
        }
    }

    /**
     * 处理数据接收失败
     */
    private void handleDataFailure() {
        log.error("[数据接收失败] 连接ID:{} 数据ID:{}", getConnectionId(), getDataId());
        //删除这个数据
        deleteReceiveDataByConnectIdAndDataId(getConnectionId(), getDataId());
        // 取消所有定时器
        cancelAllRetransmitTimers();
        // 释放帧资源
        releaseFrames();
        // 执行失败回调
        if (failCallback != null) {
            failCallback.run();
        }
    }

    private void cancelAllRetransmitTimers() {
        //取消全局
        //取消所有的单个
        if (globalTimeout != null) {
            globalTimeout.cancel();
            globalTimeout = null; // 清空引用，帮助GC
        }
        // 遍历并取消所有定时器
        retransmitTimers.values().forEach(Timeout::cancel);
        // 清空集合，帮助GC
        retransmitTimers.clear();
        retransmitCounts.clear();
    }

    private void releaseFrames() {
        QuicFrame[] frameArray = getFrameArray();
        if (frameArray == null) return;
        // 遍历释放已接收的QuicFrame（避免内存泄漏）
        for (QuicFrame frame : frameArray) {
            if (frame != null) {
                frame.release(); // 假设QuicFrame有release方法释放内部资源
            }
        }
        // 清空数组引用，帮助GC
        setFrameArray(null);
        // 清空已接收集合
        receivedSequences.clear();
    }


    // 取消单个重传定时器
    private void cancelRetransmitTimer(int sequence) {
        Timeout timeout = retransmitTimers.remove(sequence);
        if (timeout != null) {
            timeout.cancel();
        }
        // 移除重传次数计数
        retransmitCounts.remove(sequence);
    }


    private void handleDataComplete() {
        if (isComplete()){
            log.info("已经完成数据交付");
            return;
        }
        setComplete(true);
        cancelAllRetransmitTimers();
        ByteBuf combinedFullData = getCombinedFullData();
        if (combinedFullData == null) { // 增加非空校验，避免空指针
            log.error("组合完整数据失败，无法处理");
            return;
        }
        try { // 使用try-finally确保释放
            byte[] bytes = new byte[combinedFullData.readableBytes()];
            combinedFullData.getBytes(combinedFullData.readerIndex(), bytes);
            log.info("combinedFullData 字节数组长度：" + bytes.length);
        } finally {
            // 关键：无论是否异常，都释放ByteBuf
            if (combinedFullData.refCnt() > 0) {
                combinedFullData.release();
                log.debug("[释放] getCombinedFullData返回的ByteBuf，连接ID:{} 数据ID:{}",
                        getConnectionId(), getDataId());
            }
        }
        this.release();
        deleteReceiveDataByConnectIdAndDataId(getConnectionId(), getDataId());
        if (successCallback != null) {
            successCallback.run();
        }
    }

    /**
     * 构建ACK帧
     * @param quicFrame 收到的数据帧
     * @return
     */
    private QuicFrame buildAckFrame(QuicFrame quicFrame) {
        long connectionId = quicFrame.getConnectionId();
        long dataId = quicFrame.getDataId();
        int sequence = quicFrame.getSequence();


        QuicFrame ackFrame = QuicFrame.acquire();
        ackFrame.setConnectionId(connectionId);
        ackFrame.setDataId(dataId);
        ackFrame.setSequence(sequence); // ACK帧序列号固定为0
        ackFrame.setTotal(1); // ACK帧不分片
        ackFrame.setFrameType(QuicFrameEnum.ACK_FRAME.getCode()); // 自定义：ACK帧类型

        // 计算总长度：固定头部 + 载荷长度
        int totalLength = QuicFrame.FIXED_HEADER_LENGTH;
        ackFrame.setFrameTotalLength(totalLength);
        ackFrame.setPayload(null);
        ackFrame.setRemoteAddress(quicFrame.getRemoteAddress());
        return ackFrame;
    }
}
