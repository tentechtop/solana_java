package com.bit.solana.p2p.quic;

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

import static com.bit.solana.p2p.quic.QuicConnectionManager.Global_Channel;
import static com.bit.solana.p2p.quic.QuicConstants.*;


@Slf4j
@Data
public class ReceiveQuicData extends QuicData {
    //是否完成接收
    private volatile boolean isComplete = false;

    // 已经接收到的帧序列号
    private final Set<Integer> receivedSequences = Collections.newSetFromMap(new ConcurrentHashMap<>());


    // 全局定时器
    private volatile Timeout globalTimeout;
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
        QuicFrame ackFrame = buildAckFrame(quicFrame);

        ByteBuf buf = QuicConstants.ALLOCATOR.buffer();
        ackFrame.encode(buf);
        DatagramPacket packet = new DatagramPacket(buf, ackFrame.getRemoteAddress());
        Global_Channel.writeAndFlush(packet);
        ackFrame.release();

        // ========== 重复帧处理 ==========
        if (receivedSequences.contains(sequence)) {
            log.debug("[重复帧] 连接ID:{} 数据ID:{} 序列号:{} 总帧数:{}，直接回复ACK",
                    connectionId, dataId, sequence, total);
            return;
        }
        if (getFrameArray() == null) {
            setFrameArray(new QuicFrame[total]);
            setTotal(total);
            setDataId(dataId);
            setConnectionId(connectionId);
            log.info("[初始化数据] 连接ID:{} 数据ID:{} 总帧数:{}", connectionId, dataId, total);
            // 启动全局超时定时器  动态全局超时 根据total动态计算
            startGlobalTimeoutTimer();
        }

        getFrameArray()[sequence] = quicFrame;
        receivedSequences.add(sequence);
        log.debug("[接收帧] 连接ID:{} 数据ID:{} 序列号:{} 已接收:{}/{}",
                connectionId, dataId, sequence, receivedSequences.size(), total);

        if (receivedSequences.size() == total) {
            log.info("所有帧接收完成");
            // 所有帧接收完成
            handleDataComplete();
        }
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
                log.debug("[全局定时器启动] 连接ID:{} 数据ID:{} 超时时间:{}ms",
                        getConnectionId(), getDataId(), GLOBAL_TIMEOUT_MS);
            }
        }
    }



    private void handleDataComplete() {
        if (isComplete()){
            log.info("已经完成数据交付");
            return;
        }
        setComplete(true);
        cancelAllRetransmitTimers();
        byte[] combinedFullData = getCombinedFullData();
        if (combinedFullData == null) { // 增加非空校验，避免空指针
            log.error("组合完整数据失败，无法处理");
            return;
        }
        //交付完整数据到下一个处理器
        log.info("完整数据长度{} 数据ID{}",combinedFullData.length,getDataId());
        R_CACHE.put(getDataId(),System.currentTimeMillis());
        deleteReceiveDataByConnectIdAndDataId(getConnectionId(), getDataId());
        if (successCallback != null) {
            successCallback.run();
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
    }

    private void releaseFrames() {
        QuicFrame[] frameArray = getFrameArray();
        if (frameArray == null) return;
        // 遍历释放已接收的QuicFrame（避免内存泄漏）
        for (QuicFrame frame : frameArray) {
            if (frame != null) {
                frame=null;
            }
        }
        // 清空数组引用，帮助GC
        setFrameArray(null);
        // 清空已接收集合
        receivedSequences.clear();
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
        QuicFrame ackFrame =  QuicFrame.acquire();
        ackFrame.setConnectionId(connectionId);
        ackFrame.setDataId(dataId);
        ackFrame.setSequence(sequence); // ACK帧序列号固定为0
        ackFrame.setTotal(1); // ACK帧不分片
        ackFrame.setFrameType(QuicFrameEnum.DATA_ACK_FRAME.getCode()); // 自定义：ACK帧类型
        ackFrame.setRemoteAddress(quicFrame.getRemoteAddress());
        // 计算总长度：固定头部 + 载荷长度
        int totalLength = QuicFrame.FIXED_HEADER_LENGTH;
        ackFrame.setFrameTotalLength(totalLength);
        ackFrame.setPayload(null);
        return ackFrame;
    }
}
