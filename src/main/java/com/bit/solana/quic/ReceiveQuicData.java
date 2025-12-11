package com.bit.solana.quic;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import io.netty.util.Timer;
import io.netty.util.TimerTask;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import java.util.concurrent.atomic.AtomicInteger;

import static com.bit.solana.quic.QuicConnectionManager.*;

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
    // ACK发送计数器（序列号→发送次数）
    private final ConcurrentHashMap<Integer, AtomicInteger> ackSendCounts = new ConcurrentHashMap<>();
    // 传输完成回调
    private Runnable successCallback;
    // 传输失败回调
    private Runnable failCallback;




    //当收到数据帧时
    public void handleFrame(ChannelHandlerContext ctx,QuicFrame quicFrame) {
        int sequence = quicFrame.getSequence();
        int total = quicFrame.getTotal();
        long dataId = quicFrame.getDataId();
        long connectionId = quicFrame.getConnectionId();
        ByteBuf payload = quicFrame.getPayload();

        // ========== 边界校验 ==========
        if (sequence < 0 || sequence >= total) {
            log.error("[非法帧] 连接ID:{} 数据ID:{} 序列号{}超出范围[0,{}]，拒绝处理",
                    connectionId, dataId, sequence, total - 1);
            //丢弃不处理
            return;
        }

        QuicFrame ackFrame=null;
        // ========== 重复帧处理 ==========
        if (receivedSequences.contains(sequence)) {
            log.info("[重复帧] 连接ID:{} 数据ID:{} 序列号:{} 总帧数:{}，直接回复ACK",
                    connectionId, dataId, sequence, total);
            // 构建ACK帧（标记为已接收）
            // 取消该帧的重传定时器（如果存在）
            cancelRetransmitTimer(sequence);
            ackFrame = buildAckFrame(quicFrame, true);
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
            startGlobalTimeoutTimer(ctx);
        }


        // 存储帧并更新计数
        getFrameArray()[sequence] = quicFrame;
        receivedSequences.add(sequence);
        log.info("[接收帧] 连接ID:{} 数据ID:{} 序列号:{} 已接收:{}/{}",
                connectionId, dataId, sequence, receivedSequences.size(), total);

        // 构建ACK帧（标记为已接收）
        ackFrame = buildAckFrame(quicFrame, true);
        // ========== 完整性校验 & 重传处理 ==========
        if (receivedSequences.size() == total) {
            // 所有帧接收完成
            handleDataComplete(ctx);
        } else {
            // 存在缺失帧，处理重传逻辑
            handleMissingFrames(ctx);
        }

        // 发送ACK帧
        QuicFrame finalAckFrame = ackFrame;
        AtomicInteger ackCount = ackSendCounts.computeIfAbsent(sequence, k -> new AtomicInteger(0));
        int currentAckCount = ackCount.incrementAndGet();
        
        // 限制ACK发送次数，避免无限重复
        if (currentAckCount <= MAX_RETRANSMIT_TIMES) {
            ctx.writeAndFlush(ackFrame).addListener(future -> {
                //无论成功失败都释放
                finalAckFrame.release();
            });
            log.debug("[发送ACK] 连接ID:{} 数据ID:{} 序列号:{} ACK发送次数:{}",
                    connectionId, dataId, sequence, currentAckCount);
        } else {
            // 超过ACK发送限制，直接释放并记录
            finalAckFrame.release();
            log.warn("[ACK发送限制] 连接ID:{} 数据ID:{} 序列号:{} ACK发送次数{}超过限制{}",
                    connectionId, dataId, sequence, currentAckCount, MAX_RETRANSMIT_TIMES);
        }
    }


    private void handleMissingFrames(ChannelHandlerContext ctx) {
        long connectionId = getConnectionId();
        long dataId = getDataId();
        int total = getTotal();
        //目前缺少哪些帧
        Set<Integer> missingSequences = new HashSet<>();
        for (int seq = 0; seq < total; seq++) {
            if (!receivedSequences.contains(seq)) { // 已接收集合中不存在的seq即为缺失
                missingSequences.add(seq);
            }
        }
        if (missingSequences.isEmpty()) {
            return; // 无缺失，直接返回
        }
        log.warn("[检测缺失帧] 连接ID:{} 数据ID:{} 总帧数:{} 缺失帧数:{} 缺失seq:{}",
                connectionId, dataId, total, missingSequences.size(), missingSequences);

        for (int missingSeq : missingSequences) {
            // 1. 获取当前帧的重传次数（默认0）
            AtomicInteger count = retransmitCounts.computeIfAbsent(missingSeq, k -> new AtomicInteger(0));
            int currentCount = count.get();

            // 2. 超过最大重传次数 → 整体传输失败
            if (currentCount >= MAX_RETRANSMIT_TIMES) {
                log.error("[重传超限] 连接ID:{} 数据ID:{} 序列号{} 重传次数{}≥{}，传输失败",
                        connectionId, dataId, missingSeq, currentCount, MAX_RETRANSMIT_TIMES);
                handleDataFailure(ctx);
                return;
            }
            // 3. 未超限 → 启动/重置单帧重传定时器，发送重传询问
            count.incrementAndGet(); // 重传次数+1
            resetSingleFrameRetransmitTimer(ctx, missingSeq, RETRANSMIT_INTERVAL_MS, currentCount + 1);
        }
    }

    /**
     * 启动/重置单帧重传定时器（发送重传询问）
     */
    private void resetSingleFrameRetransmitTimer(ChannelHandlerContext ctx, int missingSeq, long timeoutMs, int retransmitCount) {
        // 先取消该帧已有的定时器（避免重复）
        cancelRetransmitTimer(missingSeq);

        // 新建定时器
        Timeout timeout = GLOBAL_TIMER.newTimeout(new TimerTask() {
            @Override
            public void run(Timeout timeout) throws Exception {
                if (timeout.isCancelled() || isComplete()) {
                    return;
                }
                // 定时器触发 → 再次发送重传询问
                sendRetransmitAskFrame(ctx, missingSeq, retransmitCount);
            }
        }, timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        // 存入定时器映射
        retransmitTimers.put(missingSeq, timeout);
    }


    /**
     * 发送重传询问帧（核心：告诉发送方需要重传哪个seq的帧）
     */
    private void sendRetransmitAskFrame(ChannelHandlerContext ctx, int missingSeq, int retransmitCount) {
        long connectionId = getConnectionId();
        long dataId = getDataId();

        // 1. 构建重传询问载荷
        ByteBuf askPayload = QuicConstants.ALLOCATOR.buffer();
        askPayload.writeLong(dataId);          // 数据ID
        askPayload.writeInt(missingSeq);       // 缺失的序列号
        askPayload.writeInt(retransmitCount);  // 当前重传次数

        // 2. 构建重传询问帧（自定义frameType：比如0x02表示重传请求）
        QuicFrame askFrame = QuicFrame.acquire();
        askFrame.setConnectionId(connectionId);
        askFrame.setDataId(dataId);
        askFrame.setTotal(1); // 重传询问帧不分片
        askFrame.setFrameType((byte) 0x02); // 自定义：重传请求帧类型
        askFrame.setSequence(0);
        askFrame.setFrameTotalLength(QuicFrame.FIXED_HEADER_LENGTH + askPayload.readableBytes());
        askFrame.setPayload(askPayload);
        askFrame.setRemoteAddress((InetSocketAddress) ctx.channel().remoteAddress());

        // 3. 发送重传询问帧（通过ctx写出）
        ctx.writeAndFlush(askFrame).addListener(future -> {
            if (!future.isSuccess()) {
                log.error("[重传询问发送失败] 连接ID:{} 数据ID:{} 序列号{} 重传次数{}",
                        connectionId, dataId, missingSeq, retransmitCount, future.cause());
            } else {
                log.info("[发送重传询问] 连接ID:{} 数据ID:{} 序列号{} 重传次数{}",
                        connectionId, dataId, missingSeq, retransmitCount);
            }
        });
    }

    private void startGlobalTimeoutTimer(ChannelHandlerContext ctx) {
        // 双重校验锁，防止重复创建
        synchronized (this) {
            if (globalTimeout == null && !isComplete()) {
                globalTimeout = GLOBAL_TIMER.newTimeout(new TimerTask() {
                    @Override
                    public void run(Timeout timeout) throws Exception {
                        if (timeout.isCancelled() || isComplete()) {
                            return;
                        }
                        log.error("[全局超时] 连接ID:{} 数据ID:{} 在{}ms内未接收完所有帧，接收失败",
                                getConnectionId(),  getDataId(), GLOBAL_TIMEOUT_MS);
                        handleDataFailure(ctx);
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
    private void handleDataFailure(ChannelHandlerContext ctx) {
        if (this.isComplete()) {
            return;
        }
        setComplete(true);
        log.error("[数据接收失败] 连接ID:{} 数据ID:{}", getConnectionId(), getDataId());

        // 关键：失败时也要移除ReceiveMap，避免内存泄漏
        ReceiveMap.remove(getDataId());

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
        ackSendCounts.clear();
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


    private void handleDataComplete(ChannelHandlerContext ctx) {
        //交付数据到下一个处理器
        //取消所有定时器  释放资源  传递数据 ctx.fireChannelRead(completeData);
        cancelAllRetransmitTimers();
        ByteBuf combinedFullData = getCombinedFullData();
        ctx.fireChannelRead(combinedFullData);
        this.release();
        ReceiveMap.remove(getDataId());

        if (successCallback != null) {
            successCallback.run();
        }
    }

    /**
     * 构建ACK帧
     * @param quicFrame 收到的数据帧
     * @param isReceive 是否接收 true是 false否
     * @return
     */
    private QuicFrame buildAckFrame(QuicFrame quicFrame, boolean isReceive) {
        long connectionId = quicFrame.getConnectionId();
        long dataId = quicFrame.getDataId();
        int sequence = quicFrame.getSequence();
        
        // 1. 构建ACK载荷（包含已接收序列号信息，优化UDP传输效率）
        ByteBuf ackPayload = QuicConstants.ALLOCATOR.buffer();
        ackPayload.writeLong(dataId);          // 关联的原始数据ID
        ackPayload.writeInt(sequence);         // 关联的原始帧序列号
        ackPayload.writeBoolean(isReceive);    // 是否成功接收
        ackPayload.writeInt(receivedSequences.size()); // 已接收的总帧数（辅助发送方判断）
        
        // UDP优化：批量通知已接收的序列号（可选，根据实际需要）
        if (receivedSequences.size() > 10) { // 当接收帧较多时发送批量信息
            ackPayload.writeInt(receivedSequences.size()); // 批量数量
            for (int receivedSeq : receivedSequences) {
                ackPayload.writeInt(receivedSeq); // 已接收的序列号
            }
        } else {
            ackPayload.writeInt(0); // 无批量信息
        }

        QuicFrame ackFrame = QuicFrame.acquire();
        ackFrame.setConnectionId(connectionId);
        ackFrame.setDataId(dataId);
        ackFrame.setTotal(1); // ACK帧不分片
        ackFrame.setFrameType((byte) 0x01); // 自定义：ACK帧类型
        ackFrame.setSequence(0); // ACK帧序列号固定为0

        // 计算总长度：固定头部 + 载荷长度
        int totalLength = QuicFrame.FIXED_HEADER_LENGTH + ackPayload.readableBytes();
        ackFrame.setFrameTotalLength(totalLength);
        ackFrame.setPayload(ackPayload);
        ackFrame.setRemoteAddress(quicFrame.getRemoteAddress());

        return ackFrame;
    }
}
