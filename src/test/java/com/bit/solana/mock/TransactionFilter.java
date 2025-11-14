package com.bit.solana.mock;

import java.util.*;
import java.util.stream.Collectors;

public class TransactionFilter {

    /**
     * 从缓冲池筛选出手续费最高的前5000笔交易
     * @param bufferPool 缓冲池中的所有交易（最多100万笔）
     * @return 手续费最高的前5000笔（或全部，若总数不足5000）
     */
    public List<Transaction> filterTop5000Fees(List<Transaction> bufferPool) {
        // 边界条件：缓冲池为空，返回空列表
        if (bufferPool == null || bufferPool.isEmpty()) {
            return Collections.emptyList();
        }

        int targetSize = 5000;
        int actualSize = bufferPool.size();

        // 若交易总数≤5000，直接排序后返回
        if (actualSize <= targetSize) {
            return bufferPool.stream()
                    .sorted((t1, t2) -> Long.compare(t2.getFee(), t1.getFee())) // 降序排序
                    .collect(Collectors.toList());
        }

        // 交易总数>5000，使用小顶堆筛选前5000
        // 小顶堆的比较器：按手续费升序（堆顶为当前最小）
        PriorityQueue<Transaction> minHeap = new PriorityQueue<>(
                targetSize,
                Comparator.comparingLong(Transaction::getFee)
        );

        for (Transaction transaction : bufferPool) {
            long currentFee = transaction.getFee();
            // 堆未满时直接加入
            if (minHeap.size() < targetSize) {
                minHeap.add(transaction);
            } else {
                // 堆已满，若当前交易手续费高于堆顶（最小），则替换堆顶
                if (currentFee > minHeap.peek().getFee()) {
                    minHeap.poll(); // 移除堆顶（最小）
                    minHeap.add(transaction); // 加入当前更大的
                }
            }
        }

        // 将堆中元素转为列表并按手续费降序排列（可选，根据需求是否需要排序后的结果）
        List<Transaction> top5000 = new ArrayList<>(minHeap);
        top5000.sort((t1, t2) -> Long.compare(t2.getFee(), t1.getFee()));

        return top5000;
    }


}