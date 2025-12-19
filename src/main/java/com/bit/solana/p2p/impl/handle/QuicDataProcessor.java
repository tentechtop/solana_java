package com.bit.solana.p2p.impl.handle;

import com.bit.solana.p2p.quic.QuicConstants;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

@Component
@Slf4j
public class QuicDataProcessor {
    // 消费线程池
    private ExecutorService consumerExecutor;

    // 线程池核心配置（示例值，根据业务调整）
    private static final int CORE_POOL_SIZE = 1;    // 核心线程数
    private static final int MAX_POOL_SIZE = 2;     // 最大线程数
    private static final int KEEP_ALIVE_SECONDS = 60; // 空闲线程存活时间
    private static final int QUEUE_CAPACITY = 100;  // 线程池任务队列容量
    private static final String THREAD_PREFIX = "quic-consumer-"; // 线程名前缀

    /**
     * 初始化：启动消费线程池
     */
    @PostConstruct
    public void initConsumer() {
        // 构建线程池（有界队列+拒绝策略，避免OOM/消息丢失）
        consumerExecutor = new ThreadPoolExecutor(
                CORE_POOL_SIZE,
                MAX_POOL_SIZE,
                KEEP_ALIVE_SECONDS,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                // 自定义线程工厂（设置名称+守护线程）
                r -> {
                    Thread thread = new Thread(r, THREAD_PREFIX + System.currentTimeMillis());
                    thread.setDaemon(true); // 应用关闭时自动退出
                    return thread;
                },
                new ThreadPoolExecutor.CallerRunsPolicy() // 拒绝策略：调用者兜底，避免丢消息
        );

        // 提交批量消费任务
        consumerExecutor.submit(this::batchConsume);
        log.info("Quic批量消费线程池初始化完成");
    }

    /**
     * 核心逻辑：阻塞等待消息 → 批量提取全部 → 批量处理
     * 优势：无消息时阻塞（CPU≈0），有消息时批量处理（提升效率）
     */
    private void batchConsume() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                // 1. 阻塞等待至少1条消息（无消息时挂起，不浪费CPU）
                byte[] firstMsg = QuicConstants.takeMsg();
                log.debug("获取到首条消息，开始批量提取剩余消息");

                // 2. 批量提取队列中所有剩余消息（原子操作，非阻塞）
                List<byte[]> remainingMsgs = QuicConstants.drainAllMsg();

                // 3. 合并首条+剩余消息，形成完整批量列表
                List<byte[]> batchMsgs = new ArrayList<>(remainingMsgs.size() + 1);
                batchMsgs.add(firstMsg);
                batchMsgs.addAll(remainingMsgs);

                // 4. 批量处理消息
                processBatch(batchMsgs);
                log.info("本次批量处理完成，共处理{}条消息", batchMsgs.size());

            } catch (InterruptedException e) {
                // 线程被中断，优雅退出循环
                log.warn("消费线程被中断，准备退出", e);
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // 非中断异常：记录日志，继续消费（避免单条异常终止整个消费）
                log.error("批量消费过程中发生异常", e);
            }
        }
    }

    /**
     * 批量处理消息（核心业务逻辑，根据实际需求修改）
     * @param batchMsgs 批量消息列表（至少1条）
     */
    private void processBatch(List<byte[]> batchMsgs) {
        // 示例：遍历处理每条消息（可替换为批量入库、批量转发等）
        for (int i = 0; i < batchMsgs.size(); i++) {
            byte[] msg = batchMsgs.get(i);
            try {
                // 处理单条消息（解析、校验、业务处理等）
                processSingleMsg(msg, i + 1);
            } catch (Exception e) {
                // 单条消息失败不影响整体，仅记录日志
                log.error("处理第{}条消息失败（长度：{}字节）", i + 1, msg.length, e);
            }
        }
    }

    /**
     * 处理单条消息的具体业务逻辑（替换为你的真实业务）
     * @param msg 单条Quic消息
     * @param index 批量中的序号（便于排查问题）
     */
    private void processSingleMsg(byte[] msg, int index) {
        // 示例逻辑：打印消息长度（实际场景：解析Protobuf/JSON、写入DB、调用RPC等）
        log.info("处理第{}条消息，字节长度：{}", index, msg.length);

        // TODO 替换为真实业务逻辑：
        // 1. 解析二进制消息（如protobuf反序列化）
        // 2. 业务校验（如签名、长度校验）
        // 3. 数据入库/转发/计算等

        //回复节点  通过节点ID找到连接 并发送信息  将消息分发到对应的处理器处理即可 处理器如果有返回值 就调用协议返回即可




    }

    /**
     * 销毁：优雅关闭线程池
     */
    @PreDestroy
    public void destroyConsumer() {
        if (consumerExecutor == null) return;

        log.info("开始关闭Quic消费线程池");
        consumerExecutor.shutdown(); // 停止接收新任务
        try {
            // 等待5秒让现有任务执行完毕，超时则强制关闭
            if (!consumerExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("线程池关闭超时，强制终止剩余任务");
                consumerExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            log.error("等待线程池关闭时被中断", e);
            consumerExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("Quic消费线程池已关闭");
    }

    // ========== 可选：对外提供手动批量提取方法（供监控/测试） ==========
    public List<byte[]> manualDrainAll() {
        return QuicConstants.drainAllMsg();
    }
}