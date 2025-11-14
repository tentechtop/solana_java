package com.bit.solana.mock;

import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PressureTest {
    /**
     * 目标是从大量数据中取 TopN（5000），核心思路固定 —— 用小顶堆维护 “当前最优的 5000 笔”，遍历所有数据时不断更新堆，最终堆中数据就是结果。逻辑单一，无需复杂分支。
     * Java 内置的PriorityQueue就是现成的堆实现，无需自己手写堆结构，只需定义比较器（按手续费排序），几行代码即可完成核心筛选。
     */

    /**
     * 要将筛选 5000 笔最高手续费交易的耗时控制在 30ms 内，需要从数据结构、算法优化、硬件利用三个维度深度优化，结合底层实现细节减少冗余操作，以下是具体方案：
     * 一、核心优化思路：用「局部有序 + 并行计算」替代单线程堆遍历
     * 单线程处理 100 万数据的瓶颈在于遍历和堆调整的串行执行，30ms 内需要将总操作效率提升 3-10 倍（相比原 0.1-0.3 秒），必须通过并行化和数据结构精简突破。
     * 二、具体实现方案
     * 1. 数据结构：用数组替代环形队列 + PriorityQueue，减少内存开销
     * 环形队列存储优化：将交易数据用连续内存数组存储（而非链表或复杂队列结构），且仅保留「手续费」字段（或用原始类型 long 存储手续费，避免对象引用开销）。
     * 原因：连续内存的缓存命中率接近 100%（现代 CPU L3 缓存可容纳 100 万 long 类型数据，约 8MB），遍历速度比分散内存快 5-10 倍。
     * 抛弃 PriorityQueue，手动实现轻量小顶堆：
     * 用数组实现固定容量 5000 的小顶堆（仅存储手续费值），堆操作直接通过数组下标计算（避免 Java 集合的包装类、锁竞争等开销），单条插入 / 弹出操作耗时可从～10ns 降至～2ns。
     * 2. 算法优化：分片并行筛选 + 全局合并
     * 将 100 万数据分片，多线程并行处理，最后合并结果，充分利用多核 CPU：
     * 分片策略：按 CPU 核心数（如 8 核）将 100 万数据分为 8 片（每片 12.5 万条）。
     * 并行筛选：每个线程处理 1 片数据，用局部小顶堆筛选出该片的 Top 5000（单线程处理 12.5 万条，操作量 12.5 万 ×log2 (5000)≈162.5 万次，耗时约 0.2ms / 线程）。
     * 全局合并：将 8 个局部 Top 5000（共 4 万条）再用一个小顶堆筛选出最终 Top 5000（操作量 4 万 ×log2 (5000)≈52 万次，耗时约 0.05ms）。
     * 总耗时≈单线程分片处理时间（取最长的分片耗时）+ 合并时间 ≈ 0.2ms + 0.05ms = 0.25ms（理论值，实际受线程调度影响）。
     * 3. 硬件与执行优化：榨干 CPU 性能
     * 禁用 GC 干扰：若用 Java，将交易数据和堆数组分配在堆外内存（如 DirectByteBuffer），避免筛选过程中触发 GC 暂停（GC 一次可能耗时 10-100ms）。
     * CPU 绑定：将处理线程绑定到独立 CPU 核心（如 Linux 的taskset），减少线程切换开销。
     * 指令优化：
     * 用原始类型（long）存储手续费，避免自动装箱 / 拆箱（节省～30% 时间）。
     * 循环展开：遍历数据时手动展开循环（如每次处理 8 条数据），减少 CPU 分支预测开销。
     * 预加载数据：利用 CPU 预取指令（如__builtin_prefetch），提前将下一片数据加载到缓存。
     * 三、耗时验证与边界条件
     * 最佳情况：8 核 CPU、连续内存、无 GC、线程无切换，总耗时可控制在1-5ms（远低于 30ms）。
     * ** worst case**：数据分散在内存（缓存命中率低）、4 核 CPU、线程调度延迟，耗时约10-20ms（仍满足 30ms 要求）。
     * 风险点：若交易数据包含复杂字段（非仅手续费），需提前提取手续费到单独数组（预处理耗时可忽略，因只需一次）；若数据实时写入环形队列，需加锁保护，但可通过双缓冲（读写分离）避免筛选时阻塞写入。
     * @param args
     * @throws InterruptedException
     */



    public static void main(String[] args) throws InterruptedException {
        HighConcurrentTransactionFilter filter = new HighConcurrentTransactionFilter();
        Random random = new Random();
        int threadCount = 10; // 10个写入线程模拟并发
        ExecutorService writerPool = Executors.newFixedThreadPool(threadCount);

        // 每个线程每秒写入500-1000笔（总5000-10000笔/秒）
        for (int i = 0; i < threadCount; i++) {
            writerPool.submit(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    String id = UUID.randomUUID().toString();
                    long fee = random.nextLong(100000) + 1; // 随机手续费
                    filter.addNewTransaction(new Transaction(id, fee));
                    // 控制写入速率：500-1000笔/秒/线程
                    try {
                        Thread.sleep(random.nextInt(2) + 1); // 1-2ms一笔，即500-1000笔/秒
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
        }

        // 运行30秒后停止
        Thread.sleep(30000);
        writerPool.shutdownNow();
        filter.shutdown();
    }
}