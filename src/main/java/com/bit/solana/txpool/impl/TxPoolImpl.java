package com.bit.solana.txpool.impl;

import com.bit.solana.result.Result;
import com.bit.solana.structure.tx.Transaction;
import com.bit.solana.structure.tx.TransactionGroup;
import com.bit.solana.structure.tx.TransactionStatus;
import com.bit.solana.txpool.TxPool;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.w3c.dom.css.Counter;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Slf4j
@Service
public class TxPoolImpl implements TxPool {
    // 分片数量（建议为2的幂，哈希分布更均匀）
    private static final int SHARD_COUNT = 32;
    // 等待队列容量
    private static final int QUEUE_CAPACITY = 100_000;
    // 失败重试次数
    private static final int MAX_RETRY = 3;
    // 处理超时时间  500毫秒
    private static final long TIMEOUT_MILLIS = 500;
    // 交易状态追踪（原子操作保证线程安全）
    private final ConcurrentMap<byte[], TransactionStatus> txStatusMap = new ConcurrentHashMap<>();
    // 阻塞队列用于削峰填谷（防止突发流量击垮系统）
    private final BlockingQueue<Transaction> submitQueue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    // 并行处理线程池（工作窃取算法，适合大量小任务）
    private final ExecutorService processingPool = new ForkJoinPool(
            Runtime.getRuntime().availableProcessors() * 2,
            ForkJoinPool.defaultForkJoinWorkerThreadFactory,
            (t, e) -> log.error("交易处理线程异常", e),
            true
    );
    // 提交线程池（独立线程池隔离提交与处理）
    private final ExecutorService submitPool = new ThreadPoolExecutor(
            20, 100,
            60L, TimeUnit.SECONDS,
            new SynchronousQueue<>(),
            new ThreadFactory() {
                private final AtomicInteger counter = new AtomicInteger();
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "tx-submit-" + counter.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }
            },
            new ThreadPoolExecutor.CallerRunsPolicy() // 提交者重试机制，防止队列溢出
    );

    // 交易池分片（每个分片独立锁）
    private final List<Map<byte[], Transaction>> txShards = new ArrayList<>();
    // 交易组分片（按groupId哈希分片）
    private final List<ConcurrentMap<byte[], TransactionGroup>> groupShards = new ArrayList<>();
    
    //交易池
    private final Map<byte[], Transaction> txMap  = new ConcurrentHashMap<>();
    // 交易分组（groupId -> 交易组，groupId通常为sender账户）
    private final ConcurrentMap<byte[], TransactionGroup> txGroups = new ConcurrentHashMap<>();
    // 并行处理线程池（核心线程数=CPU核心数）
    private final ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());


    @PostConstruct
    public void init(){
        // 初始化分片
        for (int i = 0; i < SHARD_COUNT; i++) {
            txShards.add(new ConcurrentHashMap<>());
            groupShards.add(new ConcurrentHashMap<>());
        }
    }

    // 分片定位（根据byte[]的哈希值）
    private int getShardIndex(byte[] key) {
        return Math.abs(Arrays.hashCode(key) % SHARD_COUNT);
    }


    @Override
    public Result getTxPool() {
        return null;
    }

    @Override
    public Result getTxPoolStatus() {
        return null;
    }

    /**
     * 异步验证 + 预验证
     * 轻量预验证（格式、签名合法性）在addTransaction时同步快速完成，过滤明显无效的交易。
     * 重量级验证（余额检查、双花检测）交给线程池异步执行，验证通过后再正式加入交易池。
     * @param tx
     * @return
     */
    @Override
    public Result verifyTransaction(Transaction tx)  {
        // 1. 轻量预验证（同步，快速失败）
        Result<String> preCheck = preVerify(tx);
        if (!preCheck.isSuccess()) {
            return preCheck;
        }
        // 2. 异步执行重量级验证+加入交易池
        executor.submit(() -> {
            Result<String> fullCheck = fullVerify(tx);
            if (fullCheck.isSuccess()) {
                // 验证通过，加入交易池（线程安全的分片操作）
                int shardIndex = getShardIndex(tx.getTxId());
                txShards.get(shardIndex).put(tx.getTxId(), tx);
                // 加入交易组（同理按groupId分片）
                byte[] groupId = tx.getSender();
                int groupShardIndex = getShardIndex(groupId);
                groupShards.get(groupShardIndex)
                        .computeIfAbsent(groupId, k -> new TransactionGroup(groupId))
                        .getTransactions()
                        .add(tx);
            }
        });


        Result<String> stringResult = new Result<>();
        return Result.OK("交易已接受，正在验证");
    }

    /**
     * 带超时控制
     * @param tx
     * @return
     */
    @Override
    public Result addTransaction(Transaction tx) {
        // 1. 生成POH时间戳

        // 2. 加入交易池
        txMap.put(tx.getTxId(), tx);
        // 3. 按发送者（sender）分组：同一发送者的交易划入同一组
        byte[] groupId = tx.getSender();  // 分组依据：发送者账户（关键冲突账户）
        // 若组不存在则创建，存在则直接添加交易
        txGroups.computeIfAbsent(groupId, k -> new TransactionGroup(groupId))
                .getTransactions()
                .add(tx);

        return Result.OK();
    }

    /**
     * 不同组 无冲突 可并行
     */
    @Override
    public void processTransactions() {
        // 1. 从所有分片收集待处理的交易组（过滤空组）
        List<TransactionGroup> groups = groupShards.stream()
                .flatMap(shard -> shard.values().stream())
                .filter(group -> !group.getTransactions().isEmpty())
                .toList();


    }


    /**
     * 处理单个交易组（组内交易串行处理）
     * @param group
     */
    private void processGroup(TransactionGroup group) {
        // 按POH时间戳排序组内交易（确保顺序性）
        // 串行处理组内交易
        // 交易验证（签名校验、余额检查等）
    }


    // 轻量预验证（格式、签名结构等）
    private Result<String> preVerify(Transaction tx) {
        if (tx.getSignatures() == null || tx.getSignatures().isEmpty()) {
            return Result.error("缺少签名");
        }


        // 其他快速检查...
        return Result.OK();
    }

    //重量级验证
    private Result<String> fullVerify(Transaction tx) {
        return Result.OK();
    }
}
