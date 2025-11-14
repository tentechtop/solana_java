package com.bit.solana.mock;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class BatchProcessingDemo {
    public static void main(String[] args) {
        // 初始化缓冲池（100万笔交易，带唯一ID）
        List<Transaction> bufferPool = new ArrayList<>(1000000);
        Random random = new Random();
        for (int i = 0; i < 1000000; i++) {
            bufferPool.add(new Transaction("TX" + i, random.nextLong(100000) + 1));
        }

        TransactionBatchFilter filter = new TransactionBatchFilter();
        int batchNum = 1;

        // 循环筛选，直到缓冲池为空
        while (!bufferPool.isEmpty()) {
            List<Transaction> top5000 = filter.filterAndRemoveTop5000(bufferPool);
            System.out.printf("第%d批筛选完成，数量：%d，剩余交易：%d%n",
                    batchNum, top5000.size(), bufferPool.size());
            batchNum++;
        }
    }
}
