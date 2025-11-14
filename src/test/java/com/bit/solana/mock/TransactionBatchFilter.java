package com.bit.solana.mock;
import java.util.*;
import java.util.stream.Collectors;

public class TransactionBatchFilter {
    // 复用小顶堆（容量固定为5000），避免重复创建
    private final PriorityQueue<Transaction> minHeap;
    private static final int BATCH_SIZE = 5000; // 每次筛选的数量

    public TransactionBatchFilter() {
        // 初始化小顶堆（按手续费升序，堆顶为当前最小）
        this.minHeap = new PriorityQueue<>(BATCH_SIZE, Comparator.comparingLong(Transaction::getFee));
    }

    /**
     * 从当前缓冲池中筛选出Top5000手续费的交易，并从缓冲池中移除它们
     * @param bufferPool 缓冲池（剩余交易的列表，会被修改）
     * @return 本次筛选出的Top5000交易（或剩余全部）
     */
    public List<Transaction> filterAndRemoveTop5000(List<Transaction> bufferPool) {
        if (bufferPool == null || bufferPool.isEmpty()) {
            return Collections.emptyList();
        }

        int remaining = bufferPool.size();
        List<Transaction> result;

        if (remaining <= BATCH_SIZE) {
            // 剩余交易不足5000，全选并清空缓冲池
            result = bufferPool.stream()
                    .sorted((t1, t2) -> Long.compare(t2.getFee(), t1.getFee()))
                    .collect(Collectors.toList());
            bufferPool.clear(); // 清空缓冲池（已无剩余）
            return result;
        }

        // 剩余交易>5000，用小顶堆筛选Top5000
        minHeap.clear(); // 清空上次筛选的堆数据，复用堆实例

        for (Transaction transaction : bufferPool) {
            long currentFee = transaction.getFee();
            if (minHeap.size() < BATCH_SIZE) {
                minHeap.add(transaction);
            } else if (currentFee > minHeap.peek().getFee()) {
                minHeap.poll();
                minHeap.add(transaction);
            }
        }

        // 堆中即为本次Top5000，转为列表并排序（降序）
        result = new ArrayList<>(minHeap);
        result.sort((t1, t2) -> Long.compare(t2.getFee(), t1.getFee()));

        // 从缓冲池中移除已筛选出的5000笔（关键步骤）
        // 为提高删除效率，先将结果转为Set（需重写Transaction的equals和hashCode）
        Set<Transaction> toRemove = new HashSet<>(result);
        bufferPool.removeAll(toRemove); // 移除已选中的交易

        return result;
    }
}