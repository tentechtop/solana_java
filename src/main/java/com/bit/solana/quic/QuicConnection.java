package com.bit.solana.quic;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import io.netty.util.Timer;
import io.netty.util.TimerTask;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.checkerframework.checker.nullness.qual.PolyNull;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.bit.solana.quic.QuicConstants.*;


@Slf4j
@Data
public class QuicConnection {
    private long connectionId;// 连接ID
    private DatagramChannel channel;// UDP通道
    private Channel tcpChannel;// TCP通道
    private boolean isUDP = true;//默认使用可靠UDP
    private  InetSocketAddress localAddress;// 本地地址
    private  volatile InetSocketAddress remoteAddress; // 远程地址,支持连接迁移


    //数据ID->具体的数据
    private  Map<Long, QuicData> sendMap = new ConcurrentHashMap<>();
    private  Map<Long, QuicData> receiveMap = new ConcurrentHashMap<>();



    /**
     * 创建Quic连接 根据 通道 节点ID ip地址
     */
    public QuicConnection(Long connectionId,DatagramChannel channel,InetSocketAddress localAddress,InetSocketAddress remoteAddress) {
        this.connectionId = connectionId;
        this.channel = channel;
        this.remoteAddress = remoteAddress;
        this.localAddress = localAddress;
    }





    //二进制数据->QuicData  MAX_FRAME_PAYLOAD=1280字节
    /**
     * 核心方法：二进制数据转换为QuicData（按1280字节分片）
     * @param connectionId 绑定的连接ID（非0）
     * @param dataId       业务数据ID（非0，唯一标识该二进制数据）
     * @param binaryData   待分片的二进制数据（ByteBuf，需可读）
     * @return 初始化完成的QuicData（包含所有分片帧）
     * @throws IllegalArgumentException 入参非法时抛出
     */
    public static QuicData binaryToQuicData(long connectionId, long dataId, ByteBuf binaryData,boolean isSend) {
        // ========== 1. 入参防御性校验 ==========
        if (connectionId <= 0) {
            throw new IllegalArgumentException("连接ID非法：connectionId=" + connectionId);
        }
        if (dataId <= 0) {
            throw new IllegalArgumentException("业务数据ID非法：dataId=" + dataId);
        }
        if (binaryData == null || !binaryData.isReadable()) {
            throw new IllegalArgumentException("二进制数据为空或不可读");
        }

        // ========== 2. 计算分片总数 + 预校验 ==========
        int totalBytes = binaryData.readableBytes();
        // 向上取整计算总分片数（1280字节/帧）
        int totalFrames = (int) Math.ceil((double) totalBytes / MAX_FRAME_PAYLOAD);
        if (totalFrames <= 0) {
            throw new IllegalStateException("分片总数计算异常：totalBytes=" + totalBytes + "，单帧大小=" + MAX_FRAME_PAYLOAD);
        }
        log.info("开始拆分二进制数据：connId={}, dataId={}，总字节数={}，单帧最大={}字节，总分片数={}",
                connectionId, dataId, totalBytes, MAX_FRAME_PAYLOAD, totalFrames);

        // ========== 3. 拆分二进制数据为QuicFrame（零拷贝+引用安全） ==========
        QuicFrame[] frames = new QuicFrame[totalFrames];
        int sequence = 0;
        int remainingBytes = totalBytes;

        // 保存原始readerIndex，避免修改外部传入的ByteBuf指针
        int originalReaderIndex = binaryData.readerIndex();
        try {
            while (remainingBytes > 0 && sequence < totalFrames) {
                // 计算当前帧的负载大小（最后一帧可能不足1280）
                int currentFrameSize = Math.min(remainingBytes, MAX_FRAME_PAYLOAD);

                // 零拷贝切片（不复制数据，仅引用），retain保证外部释放时当前slice不失效
                ByteBuf framePayload = binaryData.readSlice(currentFrameSize).retain();

                // 创建QuicFrame并设置核心字段
                QuicFrame frame = QuicFrame.acquire();
                frame.setConnectionId(connectionId);
                frame.setDataId(dataId);
                frame.setTotal(totalFrames);
                frame.setSequence(sequence);
                frame.setPayload(framePayload);
                frame.setFrameType(QuicFrameEnum.DATA_FRAME.getCode()); // 数据帧类型（常量定义）// 关键修复：计算并设置帧总长度（固定头部+payload长度）
                frame.setFrameTotalLength(QuicFrame.FIXED_HEADER_LENGTH + framePayload.readableBytes()); // 新增这行


                // 校验帧有效性
                // 校验帧有效性（此时会校验frameTotalLength，见修复2）
                if (!frame.isValid()) {
                    throw new IllegalStateException("创建分片帧失败：seq=" + sequence + "，payload大小=" + framePayload.readableBytes());
                }


                frames[sequence] = frame;
                log.debug("创建分片帧成功：connId={}, dataId={}，seq={}/{}，payload大小={}字节",
                        connectionId, dataId, sequence, totalFrames - 1, currentFrameSize);

                // 迭代更新
                remainingBytes -= currentFrameSize;
                sequence++;
            }
        } catch (Exception e) {
            // 异常时释放已创建的帧，避免内存泄漏
            for (QuicFrame frame : frames) {
                if (frame != null) {
                    frame.release();
                }
            }
            binaryData.readerIndex(originalReaderIndex); // 恢复指针
            log.error("拆分二进制数据为QuicFrame失败", e);
            throw new RuntimeException("分片失败", e);
        }

        // ========== 4. 初始化QuicData并添加所有帧 ==========
        QuicData quicData = null;
        try {
            // 用第一个帧初始化QuicData（你的构造方法已自动添加第一个帧）
            quicData = new QuicData(frames[0]);
            quicData.setSend(isSend);

            // 批量添加剩余帧（从seq=1开始）
            for (int i = 1; i < frames.length; i++) {
                QuicFrame frame = frames[i];
                if (frame == null) {
                    log.warn("分片帧seq={}为空，跳过添加", i);
                    continue;
                }
                boolean addSuccess = quicData.addQuicFrame(frame);
                if (!addSuccess) {
                    log.warn("添加分片帧失败：connId={}, dataId={}，seq={}",
                            connectionId, dataId, i);
                }
            }

            // 校验是否完整（理论上应该完整，除非代码逻辑异常）
            if (!quicData.isComplete()) {
                log.error("QuicData初始化后未完整：已接收={}/{}",
                        quicData.getReceivedCount(), quicData.getTotal());
                quicData.releaseAll(); // 释放资源
                throw new IllegalStateException("QuicData分片不完整");
            }
        } catch (Exception e) {
            if (quicData != null) {
                quicData.releaseAll(); // 异常时释放QuicData资源
            }
            log.error("初始化QuicData失败", e);
            throw new RuntimeException("QuicData构建失败", e);
        } finally {
            binaryData.readerIndex(originalReaderIndex); // 恢复外部ByteBuf的读取指针
        }

        log.info("二进制数据转换为QuicData完成：connId={}, dataId={}，总分片={}，已接收={}，数据总长度={}字节",
                connectionId, dataId, quicData.getTotal(), quicData.getReceivedCount(), totalBytes);
        return quicData;
    }

    public static  QuicConnection create(DatagramChannel channel, InetSocketAddress local, InetSocketAddress remote) {
        long connectionId = ConnectionIdGenerator.generate(local, remote);
        return new QuicConnection(connectionId, channel, local, remote);
    }


    //发送二进制数据 将二进制数据转QuicData 并发将QuicData中数据发送


    public void sendBinaryData(long dataId, ByteBuf binaryData) {
        try {
            // 2. 二进制数据转QuicData（分片为1280字节/帧）
            QuicData quicData = binaryToQuicData(connectionId, dataId, binaryData,true);

            // 缓存QuicData（用于ACK核销）
            sendMap.put(dataId, quicData);
            QuicFrame[] frameArray = quicData.getFrameArray();
            int totalFrames = frameArray.length;
            log.info("开始并发发送数据：connId={}, dataId={}，总帧数={}，发送方式={}",
                    connectionId, dataId, totalFrames, isUDP ? "UDP" : "TCP");
            // 3. 分批次并发发送（避免单次发送过多导致拥塞）
            int batchSize = PUBLIC_BATCH_SIZE;
            int batchCount = (int) Math.ceil((double) totalFrames / batchSize);
            CompletableFuture<?>[] batchFutures = new CompletableFuture[batchCount];

            for (int batchIndex = 0; batchIndex < batchCount; batchIndex++) {
                int startSeq = batchIndex * batchSize;
                int endSeq = Math.min((batchIndex + 1) * batchSize, totalFrames);
                // 每批次异步发送
                batchFutures[batchIndex] = CompletableFuture.runAsync(() -> {
                    sendFrameBatch(frameArray, startSeq, endSeq);
                }, VIRTUAL_THREAD_POOL);
            }

            //等待成功



        }catch (Exception e) {

        }
    }

    // ========== 批量发送帧（核心并发逻辑） ==========
    private void sendFrameBatch(QuicFrame[] frameArray, int startSeq, int endSeq) {
        for (int seq = startSeq; seq < endSeq; seq++) {
            QuicFrame frame = frameArray[seq];
            if (frame == null || !frame.isValid()) {
                log.warn("跳过无效帧：connId={}, dataId={}, seq={}", connectionId, frame.getDataId(), seq);
                continue;
            }

            try {
                if (isUDP) {
                    // UDP发送：封装DatagramPacket
                    sendUdpFrame(frame);
                } else {
                    // TCP发送：序列化帧（解决粘包）
                    sendTcpFrame(frame);
                }

                log.debug("发送帧成功：connId={}, dataId={}, seq={}/{}",
                        connectionId, frame.getDataId(), seq, frame.getTotal() - 1);
            } catch (Exception e) {
                log.error("发送帧失败：connId={}, dataId={}, seq={}", connectionId, frame.getDataId(), seq, e);
                // 可添加重传逻辑（比如重试3次）
                retrySendFrame(frame, 3);
            }
        }
    }


    // ========== UDP发送单帧 ==========
    void sendUdpFrame(QuicFrame frame) {
        // 序列化帧（UDP无需粘包处理，直接拼接帧头+payload）
        ByteBuf frameBuf = QuicConstants.ALLOCATOR.buffer();
        frame.encode(frameBuf); // 使用已有的 encode(ByteBuf) 方法
        DatagramPacket packet = new DatagramPacket(
                frameBuf,
                remoteAddress
        );
        // Netty UDP异步发送
        channel.writeAndFlush(packet).addListener(future -> {
            if (!future.isSuccess()) {
                // 发送完成后释放frameBuf（避免内存泄漏）
                frameBuf.release();
                throw new RuntimeException("UDP发送帧失败", future.cause());
            }
        });
    }

    // ========== TCP发送单帧（解决粘包） ==========
    void sendTcpFrame(QuicFrame frame) {
        // 序列化帧（带4字节长度头，解决粘包）
        ByteBuf tcpFrameBuf = QuicConstants.ALLOCATOR.buffer();
        frame.encode(tcpFrameBuf); // 使用已有的 encode(ByteBuf) 方法
        // Netty TCP异步发送
        tcpChannel.writeAndFlush(tcpFrameBuf).addListener(future -> {
            if (!future.isSuccess()) {
                // 发送完成后释放tcpFrameBuf
                tcpFrameBuf.release();
                throw new RuntimeException("TCP发送帧失败", future.cause());
            }
        });
    }


    // 注入Netty Timer（推荐HashedWheelTimer，需在连接关闭时停止）
    private final Timer retryTimer = new HashedWheelTimer(10, TimeUnit.MILLISECONDS);

    // ========== 帧重传逻辑（非递归+Timer重试） ==========
    private void retrySendFrame(QuicFrame frame, int retryCount) {
        if (retryCount <= 0) {
            log.error("帧重传次数耗尽：connId={}, dataId={}, seq={}",
                    connectionId, frame.getDataId(), frame.getSequence());
            frame.release(); // 释放帧内部的payload资源
            return;
        }

        // 延迟100ms重试（避免高频重试）
        retryTimer.newTimeout(timeout -> {
            try {
                if (isUDP) {
                    sendUdpFrame(frame);
                } else {
                    sendTcpFrame(frame);
                }

                log.debug("重传帧成功：connId={}, dataId={}, seq={}，剩余重试次数={}",
                        connectionId, frame.getDataId(), frame.getSequence(), retryCount - 1);
            } catch (Exception e) {
                log.warn("重传帧失败：connId={}, dataId={}, seq={}，剩余重试次数={}",
                        connectionId, frame.getDataId(), frame.getSequence(), retryCount - 1, e);
                // 递归调用继续重试（次数-1）
                retrySendFrame(frame, retryCount - 1);
            }
        }, 100, TimeUnit.MILLISECONDS);
    }



    // ========== 资源释放（可选，连接关闭时调用） ==========
    public void release() {
        // 释放所有缓存的QuicData
        sendMap.values().forEach(QuicData::releaseAll);
        sendMap.clear();

        receiveMap.values().forEach(QuicData::releaseAll);
        receiveMap.clear();
        // 关闭虚拟线程池（全局池可根据业务决定是否关闭）
        // VIRTUAL_THREAD_POOL.shutdown();
        log.info("QuicConnection资源已释放：connId={}", connectionId);
        retryTimer.stop();
    }

    public void close() {
    }

    public void handleFrame(QuicFrame quicFrame) {
        log.info("处理帧{}", quicFrame);
    }
}
