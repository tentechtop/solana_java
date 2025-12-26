package com.bit.solana.p2p.quic;

import com.bit.solana.p2p.impl.handle.QuicDataProcessor;
import com.bit.solana.p2p.protocol.NetworkHandshake;
import com.bit.solana.util.ECCWithAESGCM;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import io.netty.util.Timer;
import io.netty.util.TimerTask;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.Base58;
import org.checkerframework.checker.nullness.qual.PolyNull;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;


import static com.bit.solana.config.CommonConfig.self;
import static com.bit.solana.p2p.quic.QuicConnectionManager.*;
import static com.bit.solana.p2p.quic.QuicConstants.*;
import static com.bit.solana.p2p.quic.SendQuicData.buildFromFullData;
import static com.bit.solana.util.ByteUtils.bytesToHex;
import static com.bit.solana.util.ECCWithAESGCM.generateCurve25519KeyPair;


@Slf4j
@Data
public class QuicConnection {
    private String peerId;// 节点ID
    private long connectionId;// 连接ID
    private Channel tcpChannel;// TCP通道
    private boolean isUDP = true;//默认使用可靠UDP
    private  volatile InetSocketAddress remoteAddress; // 远程地址,支持连接迁移
    // 心跳/检查任务相关
    private TimerTask connectionTask; // 统一命名：出站=心跳任务，入站=检查任务
    private volatile Timeout connectionTimeout; // 保存定时任务引用，用于取消
    private byte[] sharedSecret;//共享加密密钥

    //全局超时时间
    public long GLOBAL_TIMEOUT_MS = 5000;
    // 帧重传间隔
    public long RETRANSMIT_INTERVAL_MS = 1000;
    //mtu  创建连接时探测
    public int MAX_FRAME_PAYLOAD = 1024 ;

    //是否过期
    private volatile boolean expired = false;
    //最后访问时间
    private volatile long lastSeen = System.currentTimeMillis();
    //true 是出站连接 false是入站连接
    private boolean isOutbound;


    private final AtomicInteger inFlightFrames = new AtomicInteger(0); // 当前在途帧数量（已发送未ACK）
    private final AtomicInteger framesSentInCurrentSecond = new AtomicInteger(0); // 当前秒内已发送帧数量
    private final AtomicInteger consecutiveAckCount = new AtomicInteger(0); // 连续ACK计数（用于触发速率提升）
    private final int maxInFlightFrames = 8192; // 单连接最大在途帧上限
    private volatile int currentSendRate = 512; // 当前发送速率（帧/秒），volatile保证多线程可见性
    private final int minSendRate = 512; // 初始/最小发送速率
    private final int maxSendRate = 8192; // 单连接最大发送速率
    private volatile long currentSecondTimestamp = System.currentTimeMillis() / 1000;; // 当前秒时间戳（用于速率计数）
    private static final int ACK_TRIGGER_THRESHOLD = 200; // 每收到200个连续ACK触发提速
    private static final float RATE_INCREMENT_FACTOR = 1.2f; // 速率提升因子（每次提升20%）
    private static final float RATE_DECREMENT_FACTOR = 0.8f; // 速率下降因子（出现异常时下降20%）

    /**
     * 判断当前连接是否可以发送单个帧
     * 需同时满足：在途帧未超限、当前秒发送速率未超限
     * @return true-可以发送，false-不可发送
     */
    public boolean canSendSingleFrame() {
        // 1. 检查在途帧是否超限
        if (inFlightFrames.get() >= maxInFlightFrames) {
            return false;
        }
        // 2. 检查当前秒发送速率是否超限（先更新时间戳，处理跨秒场景）
        updateSecondTimestamp();
        return framesSentInCurrentSecond.get() < currentSendRate;
    }
    /**
     * 更新当前秒时间戳（处理跨秒场景，重置当前秒发送计数）
     */
    private void updateSecondTimestamp() {
        long currentTime = System.currentTimeMillis() / 1000;
        // 如果当前时间已跨秒，重置当前秒发送计数和时间戳
        if (currentTime != currentSecondTimestamp) {
            currentSecondTimestamp = currentTime;
            framesSentInCurrentSecond.set(0);
        }
    }

    /**
     * 判断当前连接是否可以批量发送帧
     * @param batchSize 待批量发送的帧数量
     * @return true-可以批量发送，false-不可批量发送
     */
    public boolean canSendBatchFrames(int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("批量发送的帧数量不能小于等于0");
        }
        // 1. 检查在途帧是否会超限
        if (inFlightFrames.get() + batchSize > maxInFlightFrames) {
            return false;
        }
        // 2. 检查当前秒发送速率是否会超限
        updateSecondTimestamp();
        return framesSentInCurrentSecond.get() + batchSize <= currentSendRate;
    }

    /**
     * 单个帧发送成功后，更新统计信息
     */
    public void onFrameSent() {
        // 1. 递增在途帧数量
        inFlightFrames.incrementAndGet();
        // 2. 递增当前秒发送帧数量（先更新时间戳）
        updateSecondTimestamp();
        framesSentInCurrentSecond.incrementAndGet();
    }

    /**
     * 批量帧发送成功后，更新统计信息
     * @param batchSize 已发送的批量帧数量
     */
    public void onBatchFramesSent(int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("已发送的批量帧数量不能小于等于0");
        }
        // 1. 递增在途帧数量
        inFlightFrames.addAndGet(batchSize);
        // 2. 递增当前秒发送帧数量（先更新时间戳）
        updateSecondTimestamp();
        framesSentInCurrentSecond.addAndGet(batchSize);
    }

    /**
     * 单个帧收到ACK后，更新统计信息
     */
    public void onFrameAcked() {
        // 1. 递减在途帧数量（保证不小于0）
        inFlightFrames.updateAndGet(count -> Math.max(count - 1, 0));
        // 2. 递增连续ACK计数，并判断是否触发速率提升
        int currentAckCount = consecutiveAckCount.incrementAndGet();
        if (currentAckCount >= ACK_TRIGGER_THRESHOLD) {
            adjustSendRate(true); // 触发速率提升
            consecutiveAckCount.set(0); // 重置连续ACK计数
        }
    }

    /**
     * 批量帧收到ACK后，更新统计信息
     * @param batchSize 已ACK的批量帧数量
     */
    public void onBatchFramesAcked(int batchSize) {
        if (batchSize <= 0) {
            return;
        }
        // 1. 递减在途帧数量（保证不小于0）
        inFlightFrames.updateAndGet(count -> Math.max(count - batchSize, 0));
        // 2. 递增连续ACK计数，并判断是否触发速率提升
        int currentAckCount = consecutiveAckCount.addAndGet(batchSize);
        if (currentAckCount >= ACK_TRIGGER_THRESHOLD) {
            adjustSendRate(true); // 触发速率提升
            consecutiveAckCount.set(currentAckCount - ACK_TRIGGER_THRESHOLD); // 保留超出阈值的部分，避免重复计数
        }
    }

    /**
     * 帧发送失败/超时（未收到ACK），触发速率下降
     */
    public void onFrameSendFailed(int size) {
        consecutiveAckCount.set(0); // 重置连续ACK计数
        adjustSendRate(false); // 触发速率下降
        onFrameSendFailedWithdraw(size);
    }

    /**
     * 帧发送失败，撤回指定数量的在途帧和已发送帧统计数据
     * @param withdrawCount 要撤回的帧数量
     */
    public void onFrameSendFailedWithdraw(int withdrawCount) {
        // 1. 校验撤回数量合法性
        if (withdrawCount <= 0) {
            throw new IllegalArgumentException("撤回的帧数量不能小于等于0");
        }
        // 2. 原子性减少在途帧数量（保证不小于0）
        inFlightFrames.updateAndGet(count -> Math.max(count - withdrawCount, 0));
        // 3. 原子性减少当前秒已发送帧数量（先更新时间戳，保证计数对应当前秒，再递减）
        updateSecondTimestamp();
        framesSentInCurrentSecond.updateAndGet(count -> Math.max(count - withdrawCount, 0));
        // 4. 联动失败逻辑：重置连续ACK计数 + 触发速率下降
        consecutiveAckCount.set(0);
        adjustSendRate(false);
    }

    /**
     * 调节发送速度
     * @param isIncrement true-速率提升（基于ACK），false-速率下降（基于发送失败/超时）
     */
    private void adjustSendRate(boolean isIncrement) {
        int newRate;
        if (isIncrement) {
            // 速率提升：当前速率 * 提升因子，不超过最大速率
            newRate = (int) Math.min(currentSendRate * RATE_INCREMENT_FACTOR, maxSendRate);
        } else {
            // 速率下降：当前速率 * 下降因子，不低于最小速率
            newRate = (int) Math.max(currentSendRate * RATE_DECREMENT_FACTOR, minSendRate);
        }
        // 只有速率发生变化时才更新（避免无效赋值）
        if (newRate != currentSendRate) {
            currentSendRate = newRate;
//            System.out.printf("连接[%d]发送速率调节：%d -> %d（帧/秒）%n", connectionId, (int)(isIncrement ? newRate/RATE_INCREMENT_FACTOR : newRate/RATE_DECREMENT_FACTOR), newRate);
        }
    }



    //发送时间收集 数据大小 - > 所用时间
    private static final Map<Long, Long> connectionSendTimeMap = new ConcurrentHashMap<>();
    // 帧平均时间 纳秒（静态数组，循环缓存最多10个值，全局共享）
    private  final long[] frameAverageSendTimes = new long[1024];
    // 已经添加了多少个平均数量（原子类保证多线程安全）
    private  final AtomicInteger frameAverageSendTimesCount = new AtomicInteger(0);
    // 添加一个平均时间 总大小不能超过10个 用frameAverageSendTimesCount%10 来选定槽位（原注释%1000是笔误，数组长度为10）
    public  void addFrameAverageSendTime(long frameAverageSendTime) {
        // 1. 校验时间值有效性（避免无效数据）
        if (frameAverageSendTime < 0) {
            throw new IllegalArgumentException("帧发送时间不能为负数");
        }
        // 2. 获取当前计数并自增（原子操作，保证多线程下计数准确）
        int currentCount = frameAverageSendTimesCount.getAndIncrement();
        // 3. 计算槽位（取模数组长度，实现循环覆盖）
        int slotIndex = currentCount % frameAverageSendTimes.length;
        // 4. 存入时间值（数组赋值是原子操作，无需额外锁）
        frameAverageSendTimes[slotIndex] = frameAverageSendTime;
    }
    // 新增：获取当前帧平均发送时间（可选，方便外部使用统计数据）
    public   long getCurrentFrameAverageSendTime() {
        int arrayLength = frameAverageSendTimes.length;
        // 1. 获取有效数据个数（未填满时取实际计数，填满后取数组长度）
        int validCount = Math.min(frameAverageSendTimesCount.get(), arrayLength);
        if (validCount == 0) {
            return 0; // 无有效数据时返回0
        }
        // 2. 累加有效时间值
        long totalTime = 0;
        for (int i = 0; i < validCount; i++) {
            totalTime += frameAverageSendTimes[i];
        }
        // 3. 计算并返回平均值
        return totalTime / validCount;
    }



    /**
     * 启动连接任务（差异化：出站=主动心跳，入站=仅过期检查）
     */
    public void startHeartbeat() {
        // 避免重复启动
        if (connectionTask != null) {
            log.warn("连接任务已在运行，连接ID:{}（出站:{}）", connectionId, isOutbound);
            return;
        }

        // 分支1：出站连接 - 主动心跳（400ms）+ 过期检查
        if (isOutbound) {
            connectionTask = new TimerTask() {
                @Override
                public void run(Timeout timeout) throws Exception {
                    long now = System.currentTimeMillis();
                    // 先检查是否过期
                    if (now - lastSeen > CONNECTION_EXPIRE_TIMEOUT) {
                        markAsExpired();
                        return;
                    }

                    // 连接已失效则终止
                    if (remoteAddress == null || Global_Channel == null || expired) {
                        log.info("出站连接已关闭/过期，停止心跳，连接ID:{}", connectionId);
                        markAsExpired();
                        return;
                    }
                    QuicFrame pingFrame = QuicFrame.acquire();
                    try {
                        // 主动发送PING帧

                        long dataId = generator.nextId();
                        pingFrame.setConnectionId(connectionId);
                        pingFrame.setDataId(dataId);
                        pingFrame.setFrameType(QuicFrameEnum.PING_FRAME.getCode());
                        pingFrame.setTotal(1);
                        pingFrame.setSequence(0);
                        pingFrame.setFrameTotalLength(QuicFrame.FIXED_HEADER_LENGTH);
                        pingFrame.setRemoteAddress(remoteAddress);

                        QuicFrame quicFrame = sendFrame(pingFrame);
                        if (quicFrame == null) {
                            log.warn("出站连接PING帧发送失败，连接ID:{} 时间{}", connectionId,System.currentTimeMillis());
                        } else {
                            log.debug("出站连接PING帧发送成功，连接ID:{}，PONG帧:{}", connectionId, quicFrame);
                            updateLastSeen(); // 接收PONG更新活动时间
                            quicFrame.release();
                        }

                    } catch (Exception e) {
                        log.error("[出站心跳异常] 连接ID:{}", connectionId, e);
                    }finally {
                        pingFrame.release();
                    }

                    // 继续调度下一次心跳（未过期才继续）
                    if (!timeout.isCancelled() && !expired) {
                        connectionTimeout = HEARTBEAT_TIMER.newTimeout(this, OUTBOUND_HEARTBEAT_INTERVAL, TimeUnit.MILLISECONDS);
                    }
                }
            };
            // 启动出站心跳（400ms间隔）
            connectionTimeout = HEARTBEAT_TIMER.newTimeout(connectionTask, OUTBOUND_HEARTBEAT_INTERVAL, TimeUnit.MILLISECONDS);
            log.info("出站连接心跳任务启动，连接ID:{}，心跳间隔:{}ms，过期阈值:{}ms",
                    connectionId, OUTBOUND_HEARTBEAT_INTERVAL, CONNECTION_EXPIRE_TIMEOUT);

            // 分支2：入站连接 - 仅过期检查（1000ms），不主动发PING
        } else {
            connectionTask = new TimerTask() {
                @Override
                public void run(Timeout timeout) throws Exception {
                    long now = System.currentTimeMillis();
                    // 仅检查过期，不发送任何帧
                    if (now - lastSeen > CONNECTION_EXPIRE_TIMEOUT) {
                        markAsExpired();
                        return;
                    }

                    // 连接已失效则终止
                    if (remoteAddress == null || expired) {
                        log.info("入站连接已关闭/过期，停止检查，连接ID:{}", connectionId);
                        markAsExpired();
                        return;
                    }
                    log.debug("入站连接过期检查通过，连接ID:{}（最后活动时间:{}ms前）",
                            connectionId, now - lastSeen);

                    // 继续调度下一次检查（未过期才继续）
                    if (!timeout.isCancelled() && !expired) {
                        connectionTimeout = HEARTBEAT_TIMER.newTimeout(this, CONNECTION_EXPIRE_TIMEOUT, TimeUnit.MILLISECONDS);
                    }
                }
            };
            // 启动入站过期检查（1000ms间隔）
            connectionTimeout = HEARTBEAT_TIMER.newTimeout(connectionTask, CONNECTION_EXPIRE_TIMEOUT, TimeUnit.MILLISECONDS);
            log.info("入站连接过期检查任务启动，连接ID:{}，检查间隔:{}ms，过期阈值:{}ms",
                    connectionId, CONNECTION_EXPIRE_TIMEOUT, CONNECTION_EXPIRE_TIMEOUT);
        }
    }

    /**
     * 停止连接任务（心跳/检查）
     */
    public void stopHeartbeat() {
        // 真正取消定时任务，避免内存泄漏
        if (connectionTimeout != null) {
            connectionTimeout.cancel();
            connectionTimeout = null;
        }
        connectionTask = null;
        log.info("连接任务已停止，连接ID:{}（出站:{}）", connectionId, isOutbound);
    }


    /**
     * 更新最后访问时间（所有帧交互时调用）
     */
    public void updateLastSeen() {
        this.lastSeen = System.currentTimeMillis();
        this.expired = false; // 有活动则重置过期状态
    }

    /**
     * 标记连接过期（核心方法）
     */
    public void markAsExpired() {
        release();
    }


    //基于连接发送一次完整的数据  对方对数据中的每一个帧都回复ACK 表示数据发送成功
    public boolean sendData(byte[] data) throws InterruptedException {
        long dataId = generator.nextId();
        SendQuicData sendQuicData = buildFromFullData(connectionId, dataId, data, remoteAddress, MAX_FRAME_PAYLOAD);
        addSendDataToConnect(connectionId, sendQuicData);
        sendQuicData.sendAllFrames();
        return true;
    }



    //释放该连接
    public void release() {
        if (expired) {
            return;
        }
        expired = true;
        log.warn("连接已过期... 时间{}",System.currentTimeMillis());
        // 1. 移除连接管理器的核心引用（重中之重）
        QuicConnectionManager.removeConnection(connectionId);
        // 2. 彻底清理定时任务（避免Timer持有对象引用）
        if (connectionTimeout != null) {
            connectionTimeout.cancel();
            connectionTimeout = null;
        }
        connectionTask = null; // 清空任务引用
        // 3. 清理Netty通道相关引用（避免IO线程持有）
        if (tcpChannel != null) {
            tcpChannel.close().addListener(future -> {
                tcpChannel = null; // 通道关闭后再置空，避免空指针
            });
        }

        // 4. 清空成员变量引用（辅助GC，减少引用链）
        remoteAddress = null;

        //清除所有正在发送的数据
        deleteAllSendDataByConnectId(connectionId);
        deleteAllReceiveDataByConnectId(connectionId);
        //下线节点连接
        removePeerConnect(peerId,connectionId);
    }







    //发送二进制数据 二进制到QuicData
    //处理数据帧 返回ACK帧
    private void handleDataFrame(QuicFrame quicFrame) {
        boolean receiveDataExistInConnect = isReceiveDataExistInConnect(quicFrame.getConnectionId(), quicFrame.getDataId());
        if (receiveDataExistInConnect){
            //存在就直接获取到
            ReceiveQuicData receiveQuicData = getReceiveDataByConnectIdAndDataId(quicFrame.getConnectionId(), quicFrame.getDataId());
            receiveQuicData.handleFrame(quicFrame);
        }else {
            //是否处理过
            Long ifPresent = R_CACHE.getIfPresent(quicFrame.getDataId());
            if (ifPresent!=null){
                log.debug("数据已经处理过了");
                //回复ALL_ACK
                long connectionId = getConnectionId();
                long dataId = quicFrame.getDataId();
                QuicFrame ackFrame =  QuicFrame.acquire();
                ackFrame.setConnectionId(connectionId);
                ackFrame.setDataId(dataId);
                ackFrame.setSequence(quicFrame.getSequence()); // ACK帧序列号固定为0
                ackFrame.setTotal(1); // ACK帧不分片
                ackFrame.setFrameType(QuicFrameEnum.DATA_ACK_FRAME.getCode()); // 自定义：ACK帧类型
                ackFrame.setRemoteAddress(getRemoteAddress());
                // 计算总长度：固定头部 + 载荷长度
                int totalLength = QuicFrame.FIXED_HEADER_LENGTH;
                ackFrame.setFrameTotalLength(totalLength);
                ackFrame.setPayload(null);
                ByteBuf buf = QuicConstants.ALLOCATOR.buffer();
                ackFrame.encode(buf);
                DatagramPacket packet = new DatagramPacket(buf, ackFrame.getRemoteAddress());
                Global_Channel.writeAndFlush(packet);
                ackFrame.release();
            }else {
                ReceiveQuicData receiveDataByFrame = createReceiveDataByFrame(quicFrame);
                receiveDataByFrame.handleFrame(quicFrame);
            }
        }
    }

    //处理ACK帧


    public void handleFrame(QuicFrame quicFrame) {
        switch (QuicFrameEnum.fromCode(quicFrame.getFrameType())) {
            case DATA_FRAME:
               handleDataFrame(quicFrame);
               break;
            case DATA_ACK_FRAME:
                handleACKFrame(quicFrame);
                break;
/*            case ALL_ACK_FRAME:
                handleALLACKFrame(quicFrame);
                break;*/
            case BATCH_ACK_FRAME:
                handleBatchACKFrame(quicFrame);
                break;
            case PING_FRAME:
                handlePingFrame(quicFrame);
                break;
            case PONG_FRAME:
                handlePongFrame(quicFrame);
                break;
            case OFF_FRAME:
                handleOffFrame(quicFrame);
                break;
            case PEER_OFF_FRAME:
                handlePeerOffFrame(quicFrame);
                break;
            case CONNECT_REQUEST_FRAME:
                handleConnectRequestFrame(quicFrame);
                break;
            case CONNECT_RESPONSE_FRAME:
                handleConnectResponseFrame(quicFrame);
            default:
                break;
        }
    }



    private void handleBatchACKFrame(QuicFrame quicFrame) {
        log.debug("处理批量ACK");
        long connectionId1 = quicFrame.getConnectionId();
        long dataId = quicFrame.getDataId();
        SendQuicData sendQuicData = getSendDataByConnectIdAndDataId(connectionId1, dataId);
        // 标记该序列号为已确认
        if (sendQuicData != null){
            byte[] payload = quicFrame.getPayload();
            sendQuicData.batchAck(payload);
        }
        quicFrame.release();
    }

    private void handleALLACKFrame(QuicFrame quicFrame) {
        log.info("收到ALL_ACK");
        long connectionId1 = quicFrame.getConnectionId();
        long dataId = quicFrame.getDataId();
        int sequence = quicFrame.getSequence();
        SendQuicData sendQuicData = getSendDataByConnectIdAndDataId(connectionId1, dataId);
        // 标记该序列号为已确认
        if (sendQuicData != null){
            sendQuicData.allReceived();
        }
        quicFrame.release();
    }

    private void handlePongFrame( QuicFrame quicFrame) {
        log.debug("处理PONG帧{}",quicFrame);
        CompletableFuture<Object> ifPresent = QUIC_RESPONSE_FUTURECACHE.asMap().remove(quicFrame.getDataId());
        if (ifPresent != null) {
            ifPresent.complete(quicFrame);
        }
    }

    private void handleConnectRequestFrame(QuicFrame quicFrame) {
        QuicFrame acquire = QuicFrame.acquire();//已经释放
        try {
            long conId = quicFrame.getConnectionId();
            //发送连接响应帧
            long dataId = quicFrame.getDataId();
            byte[] payload = quicFrame.getPayload();

            //解析成握手数据
            NetworkHandshake deserialize = NetworkHandshake.deserialize(payload);

            byte[] nodeId = deserialize.getNodeId();
            String peerId = Base58.encode(nodeId);
            byte[] aPublicKey = deserialize.getSharedSecret();

            byte[][] BKeys = generateCurve25519KeyPair();
            byte[] bPrivateKey = BKeys[0];
            byte[] bPublicKey = BKeys[1];
            byte[] sharedSecret = ECCWithAESGCM.generateSharedSecret(bPrivateKey, aPublicKey);
            log.info("共享加密密钥对sharedSecret: {}", bytesToHex(sharedSecret));

            NetworkHandshake networkHandshake = new NetworkHandshake();
            networkHandshake.setNodeId(self.getId());
            networkHandshake.setSharedSecret(bPublicKey);
            byte[] serialize = networkHandshake.serialize();
            acquire.setConnectionId(conId);//生成连接ID
            acquire.setDataId(dataId);
            acquire.setTotal(1);
            acquire.setFrameType(QuicFrameEnum.CONNECT_RESPONSE_FRAME.getCode());
            acquire.setPayload(serialize);
            acquire.setFrameTotalLength(QuicFrame.FIXED_HEADER_LENGTH+serialize.length);

            ByteBuf buffer = ALLOCATOR.buffer();
            acquire.encode(buffer);
            DatagramPacket datagramPacket = new DatagramPacket(buffer, quicFrame.getRemoteAddress());
            setPeerId(peerId);
            setSharedSecret(sharedSecret);
            Global_Channel.writeAndFlush(datagramPacket).addListener(future -> {
                if (!future.isSuccess()) {
                    log.error("[连接响应发送失败] 节点ID:{}", conId, future.cause());
                } else {
                    log.info("[连接响应发送成功] 节点ID:{}", conId);
                    //将该节点上线
                    addPeerConnect(peerId,conId);
                }
            });
        }catch (Exception e){
            log.error("解析出错",e);
        }finally {
            acquire.release();
        }
    }


    private void handleConnectResponseFrame(QuicFrame quicFrame) {
        log.info("处理连接响应{}",quicFrame);
        //核销掉这个数据
        CompletableFuture<Object> ifPresent = QUIC_RESPONSE_FUTURECACHE.asMap().remove(quicFrame.getDataId());
        if (ifPresent != null) {
            ifPresent.complete(quicFrame);
        }
    }



    private void handleOffFrame(QuicFrame quicFrame) {
        log.info("处理连接下线");
        //取消心跳关闭连接
        byte[] payload = quicFrame.getPayload();
        String peerId = Base58.encode(payload);
        QuicConnection connection = getConnection(getConnectionId());
        connection.release();
        if (PeerConnect.containsKey(peerId)){
            Long conId = PeerConnect.remove(peerId);
            //释放连接
            QuicConnection quicConnection = getConnection(conId);
            if (quicConnection!=null){
                quicConnection.release();
            }
        }
        quicFrame.release();
    }

    private void handlePeerOffFrame(QuicFrame quicFrame) {
        byte[] payload = quicFrame.getPayload();
        String peerId = Base58.encode(payload);
        //删除掉该节点的所有信息并释放所有连接
        if (PeerConnect.containsKey(peerId)){
            Long conId = PeerConnect.remove(peerId);
            //释放连接
            QuicConnection quicConnection = getConnection(conId);
            if (quicConnection!=null){
                quicConnection.release();
            }
        }
        quicFrame.release();
    }

    private void handlePingFrame(QuicFrame quicFrame) {
        QuicFrame pongFrame = QuicFrame.acquire();//已经释放
        try {
            log.debug("处理ping");
            //更新访问时间
            //回复PONG帧

            pongFrame.setConnectionId(connectionId);
            pongFrame.setDataId(quicFrame.getDataId()); // 临时数据ID
            pongFrame.setFrameType(QuicFrameEnum.PONG_FRAME.getCode()); // PING_FRAME类型
            pongFrame.setTotal(1); // 单帧无需分片
            pongFrame.setSequence(0);
            pongFrame.setFrameTotalLength(QuicFrame.FIXED_HEADER_LENGTH); // 无载荷
            pongFrame.setRemoteAddress(quicFrame.getRemoteAddress());

            //编码
            ByteBuf buf = QuicConstants.ALLOCATOR.buffer();
            pongFrame.encode(buf);
            DatagramPacket packet = new DatagramPacket(buf, pongFrame.getRemoteAddress());
            Global_Channel.writeAndFlush(packet).addListener(future -> {
                if (!future.isSuccess()) {
                    log.info("回复失败{}", remoteAddress);
                } else {
                    log.debug("回复成功{}", remoteAddress);
                }
            });
        }finally {
            pongFrame.release();
            quicFrame.release();
        }
    }

    private void handleACKFrame( QuicFrame quicFrame) {
        long connectionId1 = quicFrame.getConnectionId();
        long dataId = quicFrame.getDataId();
        int sequence = quicFrame.getSequence();
        SendQuicData sendQuicData = getSendDataByConnectIdAndDataId(connectionId1, dataId);
        // 标记该序列号为已确认
        if (sendQuicData != null){
            sendQuicData.onAckReceived(sequence);
        }
        quicFrame.release();
        onFrameAcked();
    }


    public int getInFlightFrames() {
        return inFlightFrames.get();
    }

    public int getCurrentSendRate() {
        return currentSendRate;
    }

    public int getMaxInFlightFrames() {
        return maxInFlightFrames;
    }

    public int getMinSendRate() {
        return minSendRate;
    }

    public long[] getFrameAverageSendTimes() {
        return frameAverageSendTimes;
    }

}
