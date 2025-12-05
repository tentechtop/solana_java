package com.bit.solana.quic;

import com.bit.solana.util.ByteUtils;
import io.netty.buffer.ByteBuf;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import io.netty.util.Timer;
import io.netty.util.TimerTask;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import static com.bit.solana.quic.QuicConstants.*;
import static com.bit.solana.util.ByteUtils.bytesToHex;

@Slf4j
@Data
public class QuicData {
    // 基础标识（和帧的核心字段对齐）
    private final long connectionId;    // 绑定连接ID，避免跨连接混叠
    private final long dataId;         // 绑定业务单元ID
    private final int total;            // 固定分片总数（从首个帧获取）
    private boolean isSend;//这是发送方的数据


    // 核心存储：利用total预分配数组（O(1)访问，零扩容）
    private final QuicFrame[] frameArray;
    // 已接收分片数（原子计数，线程安全）  接收方使用它接收  发送方使用它核销
    private final AtomicInteger receivedCount = new AtomicInteger(0);


    // 线程安全锁（保证添加/校验的原子性）
    private final ReentrantLock lock = new ReentrantLock();
    // 完整性标记（避免重复拼接）
    private volatile boolean isComplete = false;


    //数据核销情况 每一个帧的核销情况
    private  boolean[] ackStatus;


    private final int finSequence;


    // ========== 发送方重传相关 ==========
    private final Map<Integer, AtomicInteger> frameRetryCount = new ConcurrentHashMap<>();
    private final Map<Integer, Timeout> frameTimeoutTasks = new ConcurrentHashMap<>();
    private volatile boolean isSendFailed = false;
    private static final Timer SEND_TIMER = new HashedWheelTimer(10, TimeUnit.MILLISECONDS);

    // ========== 接收方索取相关 ==========
    private volatile Timeout receiveCheckTask;
    private final Map<Integer, AtomicInteger> frameRequestCount = new ConcurrentHashMap<>();
    private static final Timer RECEIVE_TIMER = new HashedWheelTimer(10, TimeUnit.MILLISECONDS);




    // ==================== 发送方核心逻辑 ====================
    /**
     * 发送方初始化帧重传监听：50ms无ACK重传，最多3次，150ms标记失败
     */
    public void initSendRetryListener() {
        if (!isSend || isComplete || isSendFailed) {
            log.warn("无需初始化重传监听：isSend={}, isComplete={}, isSendFailed={}",
                    isSend, isComplete, isSendFailed);
            return;
        }

        log.info("初始化发送方重传监听[connId={}, dataId={}]，重传间隔{}ms，最大重传{}次",
                connectionId, dataId, SEND_RETRY_INTERVAL, SEND_MAX_RETRY);

        for (int seq = 0; seq < total; seq++) {
            frameRetryCount.putIfAbsent(seq, new AtomicInteger(0));
            startFrameRetryTask(seq);
        }
    }

    /**
     * 启动单个帧的重传任务
     */
    private void startFrameRetryTask(int sequence) {
        if (!isSend || isComplete || isSendFailed) {
            log.debug("无需启动重传任务：seq={}, isSend={}, isComplete={}",
                    sequence, isSend, isComplete);
            return;
        }

        QuicFrame frame = frameArray[sequence];
        if (frame == null) {
            log.error("帧[seq={}]为空，无法重传", sequence);
            markSendFailed("帧为空，seq=" + sequence);
            return;
        }

        TimerTask retryTask = timeout -> {
            lock.lock();
            try {
                // 已核销/完成/失败则终止
                if (ackStatus[sequence] || isComplete || isSendFailed) {
                    log.debug("帧[seq={}]无需重传，终止任务", sequence);
                    return;
                }

                AtomicInteger retryCount = frameRetryCount.get(sequence);
                int currentRetry = retryCount.getAndIncrement();

                // 超过最大重传次数，标记失败
                if (currentRetry >= SEND_MAX_RETRY) {
                    String msg = String.format("帧[seq=%d]重传%d次耗尽，标记失败", sequence, SEND_MAX_RETRY);
                    log.error(msg);
                    markSendFailed(msg);
                    return;
                }

                // 执行重传
                log.warn("帧[seq={}]第{}次重传（间隔{}ms）", sequence, currentRetry + 1, SEND_RETRY_INTERVAL);
                reSendFrame(frame);

                // 继续启动下一次重传任务
                startFrameRetryTask(sequence);
            } finally {
                lock.unlock();
            }
        };

        // 50ms后执行重传任务
        Timeout timeout = SEND_TIMER.newTimeout(retryTask, SEND_RETRY_INTERVAL, TimeUnit.MILLISECONDS);
        frameTimeoutTasks.put(sequence, timeout);
    }

    /**
     * 重传指定帧
     */
    private void reSendFrame(QuicFrame frame) {
        try {
            QuicConnection connection = QuicConnectionManager.getConnection(connectionId);
            if (connection == null) {
                markSendFailed("获取连接失败：connId=" + connectionId);
                return;
            }

            if (connection.isUDP()) {
                connection.sendUdpFrame(frame);
            } else {
                connection.sendTcpFrame(frame);
            }
            log.debug("重传帧成功[seq={}]", frame.getSequence());
        } catch (Exception e) {
            log.error("重传帧失败[seq={}]", frame.getSequence(), e);
        }
    }



    /**
     * 发送方核销
     * 核销指定序列号的帧（线程安全）
     * @param sequence 待核销的帧序列号
     * @return true=核销成功（未核销→已核销）；false=已核销/seq非法/已完成
     */
    public boolean ackFrame(int sequence) {
        if (sequence < 0 || sequence >= total || isComplete || isSendFailed) {
            log.warn("帧核销失败：seq={}越界/已完成/发送失败", sequence);
            return false;
        }

        lock.lock();
        try {
            if (ackStatus[sequence]) {
                log.debug("帧[seq={}]已核销，跳过", sequence);
                return false;
            }

            // 标记核销成功
            ackStatus[sequence] = true;
            receivedCount.incrementAndGet();
            // 取消该帧重传任务
            cancelFrameRetryTask(sequence);

            // 检查全部核销完成
            if (receivedCount.get() == total) {
                isComplete = true;
                cancelAllFrameRetryTasks();
                log.info("所有帧核销完成[connId={}, dataId={}]", connectionId, dataId);
            }

            return true;
        } finally {
            lock.unlock();
        }
    }


    /**
     * 接收方添加帧
     */
    boolean receiveAddQuicFrame(QuicFrame frame) {
        if (frame == null || isComplete) {
            log.warn("帧非法或已完成，跳过添加");
            return false;
        }

        if (frame.getConnectionId() != this.connectionId
                || frame.getDataId() != this.dataId
                || frame.getTotal() != this.total) {
            log.warn("帧参数不匹配，跳过添加");
            return false;
        }

        int sequence = frame.getSequence();
        if (sequence < 0 || sequence >= total) {
            log.warn("序列号越界，跳过添加");
            return false;
        }

        lock.lock();
        try {
            if (frameArray[sequence] != null) {
                log.debug("帧[seq={}]已存在，跳过添加", sequence);
                return false;
            }

            frameArray[sequence] = frame;
            receivedCount.incrementAndGet();

            if (receivedCount.get() == total) {
                isComplete = true;
                log.info("分片完整接收[connId={}, dataId={}]", connectionId, dataId);
            }

            return true;
        } finally {
            lock.unlock();
        }
    }


    /**
     * 取消单个帧的重传任务
     */
    private void cancelFrameRetryTask(int sequence) {
        Timeout timeout = frameTimeoutTasks.remove(sequence);
        if (timeout != null && !timeout.isExpired() && !timeout.isCancelled()) {
            timeout.cancel();
        }
        frameRetryCount.remove(sequence);
    }




    /**
     * 构造方法：从首个帧初始化核心参数（total/dataId/connectionId）
     * @param firstFrame 首个分片帧（必须包含total/dataId/connectionId）
     */
    public QuicData(QuicFrame firstFrame) {
        // 防御性校验：首个帧必须包含有效参数
        if (firstFrame == null || firstFrame.getDataId() == 0 || firstFrame.getTotal() <= 0) {
            throw new IllegalArgumentException("首个分片帧参数非法：dataId=null 或 total≤0");
        }
        this.connectionId = firstFrame.getConnectionId();
        this.dataId = firstFrame.getDataId(); // 深拷贝，避免外部修改
        this.total = firstFrame.getTotal();
        // 预分配数组：长度=total（序列号0~total-1），零扩容
        this.frameArray = new QuicFrame[this.total];

        // ========== 新增：核销状态初始化 ==========
        this.ackStatus = new boolean[this.total]; // 默认全为false（未核销）
        this.finSequence = this.total - 1; // FIN帧序列号固定为总数-1
        Arrays.fill(this.ackStatus, false); // 显式初始化，避免默认值问题

        // 直接添加首个帧（复用add逻辑）
        addQuicFrame(firstFrame);
        log.info("初始化QuicData[connId={}, dataId={}]，分片总数={}",
                connectionId, dataId, total);
    }

    /**
     * 添加分片帧：O(1)时间复杂度、去重、连接/业务单元隔离、线程安全
     * @param frame 待添加的分片帧
     * @return true=添加成功；false=重复/参数不匹配/已完成，添加失败
     */
    boolean addQuicFrame(QuicFrame frame) {
        // 1. 前置校验：帧非法/已完成，直接返回
        if (frame == null || isComplete) {
            log.warn("帧非法或已完成，跳过添加：frame={}, isComplete={}", frame, isComplete);
            return false;
        }

        // 2. 核心校验：连接ID/业务ID/分片总数必须匹配（避免跨连接/跨业务混叠）
        if (frame.getConnectionId() != this.connectionId
                || frame.getDataId()!=this.dataId
                || frame.getTotal() != this.total) {
            log.warn("帧参数不匹配：connId={}(预期{})、dataId={}(预期{})、total={}(预期{})",
                    frame.getConnectionId(), this.connectionId,
                    frame.getDataId(),this.dataId,
                    frame.getTotal(), this.total);
            return false;
        }

        // 3. 序列号校验：必须在0~total-1范围内
        int sequence = frame.getSequence();
        if (sequence < 0 || sequence >= total) {
            log.warn("序列号越界：seq={}，总数={}", sequence, total);
            return false;
        }


        lock.lock();
        try {
            // 4. 去重：该序列号已存在，返回失败
            if (frameArray[sequence] != null) {
                log.debug("分片[connId={}, dataId={}, seq={}]已存在，跳过添加",
                        connectionId, dataId, sequence);
                return false;
            }

            // 5. 存储帧+原子计数
            frameArray[sequence] = frame;
            receivedCount.incrementAndGet();

            // 6. 校验完整性：已接收数==总数，标记完成
            if (receivedCount.get() == total) {
                isComplete = true;
                log.info("分片[connId={}, dataId={}]已完整接收：总数={}，已接收={}",
                        connectionId, dataId, total, receivedCount.get());
            }

            log.debug("添加分片成功[connId={}, dataId={}]：seq={}，已接收/总数={}/{}",
                    connectionId,dataId, sequence, receivedCount.get(), total);
            return true;
        } finally {
            lock.unlock();
        }
    }




    /**
     * 拼接完整数据：仅当所有分片接收完成时拼接，保证数据完整
     * @return 完整数据ByteBuf（调用方需release）；null=未完整/拼接失败
     */
    public ByteBuf getData() {
        // 1. 未完整接收，直接返回null
        if (!isComplete) {
            log.warn("分片未完整接收，无法拼接：已接收={}/{}", receivedCount.get(), total);
            return null;
        }

        lock.lock();
        try {
            // 2. 预计算总长度（精准分配ByteBuf，零扩容）
            int totalLength = 0;
            for (QuicFrame frame : frameArray) {
                if (frame != null && frame.getPayload() != null && frame.getPayload().isReadable()) {
                    totalLength += frame.getPayload().readableBytes();
                }
            }
            if (totalLength == 0) {
                log.warn("所有分片payload均为空，总长度=0");
                return null;
            }

            // 3. 一次性分配ByteBuf（池化分配，性能最优）
            ByteBuf completeData = QuicConstants.ALLOCATOR.buffer(totalLength);

            try {
                // 4. 按序列号遍历数组（天然有序），拼接payload
                for (int seq = 0; seq < total; seq++) {
                    QuicFrame frame = frameArray[seq];
                    if (frame == null || frame.getPayload() == null || !frame.getPayload().isReadable()) {
                        // 理论上不会走到这里（isComplete已保证所有分片存在）
                        log.error("完整标记异常：seq={}的分片为空或payload无效", seq);
                        completeData.release();
                        return null;
                    }
                    // 复制payload（不修改原引用计数，避免影响帧的释放）
                    completeData.writeBytes(frame.getPayload());
                }

                // 5. 重置读指针，方便调用方读取
                completeData.readerIndex(0);
                log.info("拼接完整数据成功[connId={}, dataId={}]：总长度={}字节",
                        connectionId, dataId, totalLength);
                return completeData;
            } catch (Exception e) {
                log.error("拼接数据异常", e);
                if (completeData != null ) {
                    completeData.release();
                }
                return null;
            }
        } finally {
            lock.unlock();
        }
    }




    /**
     * 安全释放所有资源：帧数组+payload+标记清空
     * 调用时机：拼接完成后/超时丢弃时
     */
    public void releaseAll() {
        lock.lock();
        try {
            // 释放所有帧的payload
            for (QuicFrame frame : frameArray) {
                if (frame != null) {
                    frame.release();
                }
            }
            // 清空数组（帮助GC）
            Arrays.fill(frameArray, null);
            receivedCount.set(0);
            isComplete = false;
            log.info("释放QuicData[connId={}, dataId={}]所有资源",
                    connectionId,dataId);
        } finally {
            lock.unlock();
        }
    }

    // 辅助方法：获取已接收分片数（监控用）
    public int getReceivedCount() {
        return receivedCount.get();
    }

    // 辅助方法：判断是否完整接收
    public boolean isComplete() {
        return isComplete;
    }


    /**
     * 标记发送失败
     */
    public void markSendFailed(String reason) {
        if (isSendFailed) return;
        isSendFailed = true;
        isComplete = false;
        cancelAllFrameRetryTasks();
        log.error("数据发送失败[connId={}, dataId={}]：{}", connectionId, dataId, reason);
    }

    /**
     * 取消所有重传任务
     */
    private void cancelAllFrameRetryTasks() {
        frameTimeoutTasks.values().forEach(timeout -> {
            if (timeout != null && !timeout.isExpired() && !timeout.isCancelled()) {
                timeout.cancel();
            }
        });
        frameTimeoutTasks.clear();
        frameRetryCount.clear();
    }





    // ==================== 接收方核心逻辑 ====================
    /**
     * 接收方初始化缺失帧检测：20ms检测一次，主动索取缺失帧
     */
    public void initReceiveRequestListener() {
        if (isSend || isComplete) {
            log.warn("无需初始化索取监听：isSend={}, isComplete={}", isSend, isComplete);
            return;
        }

        log.info("初始化接收方索取监听[connId={}, dataId={}]，检测间隔{}ms",
                connectionId, dataId, RECEIVE_CHECK_INTERVAL);
        startReceiveCheckTask();
    }

    /**
     * 启动接收方缺失帧检测任务
     */
    private void startReceiveCheckTask() {
        if (isSend || isComplete) {
            if (receiveCheckTask != null && !receiveCheckTask.isExpired() && !receiveCheckTask.isCancelled()) {
                receiveCheckTask.cancel();
                receiveCheckTask = null;
            }
            return;
        }

        TimerTask checkTask = timeout -> {
            lock.lock();
            try {
                if (isComplete) {
                    log.info("数据完整接收，停止索取检测[connId={}, dataId={}]", connectionId, dataId);
                    receiveCheckTask = null;
                    return;
                }

                // 查找缺失的序列号
                List<Integer> missingSeqs = findMissingSequences();
                if (missingSeqs.isEmpty()) {
                    log.debug("无缺失帧，继续检测[connId={}, dataId={}]", connectionId, dataId);
                    startReceiveCheckTask();
                    return;
                }

                // 发送索取帧
                for (int seq : missingSeqs) {
                    AtomicInteger requestCount = frameRequestCount.computeIfAbsent(seq, k -> new AtomicInteger(0));
                    int currentCount = requestCount.getAndIncrement();

                    if (currentCount >= RECEIVE_MAX_REQUEST) {
                        log.error("帧[seq={}]索取{}次耗尽，标记接收失败", seq, RECEIVE_MAX_REQUEST);
                        isComplete = false;
                        receiveCheckTask = null;
                        return;
                    }

                    sendRequestFrame(seq, currentCount + 1);
                }

                // 继续下一次检测
                startReceiveCheckTask();
            } catch (Exception e) {
                log.error("接收检测异常", e);
                startReceiveCheckTask();
            } finally {
                lock.unlock();
            }
        };

        receiveCheckTask = RECEIVE_TIMER.newTimeout(checkTask, RECEIVE_CHECK_INTERVAL, TimeUnit.MILLISECONDS);
    }


    /**
     * 查找所有缺失的序列号
     */
    private List<Integer> findMissingSequences() {
        List<Integer> missingSeqs = new ArrayList<>();
        for (int seq = 0; seq < total; seq++) {
            if (frameArray[seq] == null) {
                missingSeqs.add(seq);
            }
        }
        return missingSeqs;
    }


    /**
     * 发送索取帧
     */
    private void sendRequestFrame(int sequence, int requestCount) {
        try {
            QuicConnection connection = QuicConnectionManager.getConnection(connectionId);
            if (connection == null) {
                log.error("获取连接失败，无法发送索取帧[seq={}]", sequence);
                return;
            }

            // 构建索取帧
            QuicFrame requestFrame = QuicFrame.acquire();
            requestFrame.setConnectionId(connectionId);
            requestFrame.setDataId(dataId);
            requestFrame.setTotal(total);
            requestFrame.setSequence(sequence);
            requestFrame.setFrameType(QuicFrameEnum.IMMEDIATE_REQUEST_FRAME.getCode());
            requestFrame.setFrameTotalLength(QuicFrame.FIXED_HEADER_LENGTH);

            if (!requestFrame.isValid()) {
                log.error("构建索取帧失败[seq={}]", sequence);
                requestFrame.release();
                return;
            }

            // 发送索取帧
            if (connection.isUDP()) {
                connection.sendUdpFrame(requestFrame);
            } else {
                connection.sendTcpFrame(requestFrame);
            }

            log.warn("发送索取帧[seq={}]，第{}次索取", sequence, requestCount);
            requestFrame.release();
        } catch (Exception e) {
            log.error("发送索取帧失败[seq={}]", sequence, e);
        }
    }


}
