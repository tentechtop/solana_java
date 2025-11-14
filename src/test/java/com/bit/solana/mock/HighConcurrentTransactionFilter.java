package com.bit.solana.mock;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * 高并发交易池筛选器：支持每秒5000-10000笔新增交易，定时筛选Top5000
 */
public class HighConcurrentTransactionFilter {
    private static final int BATCH_SIZE = 5000; // 每次筛选数量
    private static final long SCAN_INTERVAL_MS = 1000; // 筛选间隔（1秒，可调整）

    // 1. 新增交易缓冲队列（线程安全，高并发写入）
    private final BlockingQueue<Transaction> newTransactions = new LinkedBlockingQueue<>();

    // 2. 历史剩余交易池（用ConcurrentHashMap存储，key为交易ID避免重复，value为交易）
    private final ConcurrentMap<String, Transaction> remainingTransactions = new ConcurrentHashMap<>();

    // 3. 复用的小顶堆（避免重复创建）
    private final PriorityQueue<Transaction> minHeap = new PriorityQueue<>(
            BATCH_SIZE,
            Comparator.comparingLong(Transaction::getFee)
    );

    // 4. 定时筛选线程（每秒执行一次）
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public HighConcurrentTransactionFilter() {
        // 启动定时筛选任务
        scheduler.scheduleAtFixedRate(this::filterTop5000, 0, SCAN_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * 新增交易到缓冲队列（供外部高并发调用）
     */
    public void addNewTransaction(Transaction transaction) {
        try {
            newTransactions.put(transaction); // 阻塞直到队列有空间（可替换为offer+重试）
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 定时执行：合并新增交易到剩余池，筛选Top5000并移除
     */
    private void filterTop5000() {
        // 步骤1：将新增交易批量合并到剩余池（避免高频写入直接操作剩余池）
        mergeNewTransactions();

        // 步骤2：从剩余池中筛选Top5000
        List<Transaction> top5000 = doFilterTop5000();

        // 步骤3：处理筛选结果（如输出、持久化等）
        processResult(top5000);
    }

    /**
     * 合并新增交易到剩余池（批量处理，减少锁竞争）
     */
    private void mergeNewTransactions() {
        List<Transaction> batch = new ArrayList<>();
        // 一次性取出队列中所有新增交易（最多取当前积压的全部）
        newTransactions.drainTo(batch);
        if (batch.isEmpty()) {
            return;
        }
        // 批量加入剩余池（自动去重，基于交易ID）
        batch.forEach(tx -> remainingTransactions.put(tx.getId(), tx));
    }

    /**
     * 从剩余池中筛选Top5000手续费的交易，并移除它们
     */
    private List<Transaction> doFilterTop5000() {
        int remainingSize = remainingTransactions.size();
        if (remainingSize == 0) {
            return Collections.emptyList();
        }

        // 情况1：剩余交易≤5000，全选并清空
        if (remainingSize <= BATCH_SIZE) {
            List<Transaction> all = new ArrayList<>(remainingTransactions.values());
            all.sort((t1, t2) -> Long.compare(t2.getFee(), t1.getFee()));
            remainingTransactions.clear();
            return all;
        }

        // 情况2：剩余交易>5000，用小顶堆筛选
        minHeap.clear();
        for (Transaction tx : remainingTransactions.values()) {
            long fee = tx.getFee();
            if (minHeap.size() < BATCH_SIZE) {
                minHeap.add(tx);
            } else if (fee > minHeap.peek().getFee()) {
                minHeap.poll();
                minHeap.add(tx);
            }
        }

        // 提取Top5000并排序
        List<Transaction> top5000 = new ArrayList<>(minHeap);
        top5000.sort((t1, t2) -> Long.compare(t2.getFee(), t1.getFee()));

        // 从剩余池中移除已选中的交易（基于ID）
        top5000.forEach(tx -> remainingTransactions.remove(tx.getId()));

        return top5000;
    }

    /**
     * 处理筛选结果（示例：输出基本信息）
     */
    private void processResult(List<Transaction> top5000) {
        if (top5000.isEmpty()) {
            System.out.println("本次无筛选结果");
            return;
        }
        System.out.printf("筛选完成：数量=%d，最高手续费=%d，最低手续费（本次）=%d，剩余交易=%d%n",
                top5000.size(),
                top5000.get(0).getFee(),
                top5000.get(top5000.size() - 1).getFee(),
                remainingTransactions.size()
        );
    }

    /**
     * 关闭资源（程序退出时调用）
     */
    public void shutdown() {
        scheduler.shutdown();
    }
}