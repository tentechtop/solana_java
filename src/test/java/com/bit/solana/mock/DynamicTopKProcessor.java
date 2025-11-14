package com.bit.solana.mock;

import sun.misc.Unsafe;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class DynamicTopKProcessor {
    // 常量定义
    private static final int BUFFER_CAPACITY = 1_000_000; // 环形队列容量
    private static final int TOP_K = 5000;                // 目标Top数
    private static final int SCHEDULE_INTERVAL_MS = 400;  // 筛选间隔
    private static final int MAX_NEW_PER_INTERVAL = 4000; // 每间隔最大新增数

    // 双缓冲区（A和B）
    private final Buffer bufferA = new Buffer(BUFFER_CAPACITY);
    private final Buffer bufferB = new Buffer(BUFFER_CAPACITY);
    private final AtomicReference<Buffer> activeBuffer = new AtomicReference<>(bufferA); // 当前写入缓冲区

    // 全局Top K缓存（上一次筛选结果）
    private final AtomicReference<long[]> globalTopCache = new AtomicReference<>(new long[0]);

    // 线程池（写入线程+筛选线程）
    private final ExecutorService writerExecutor = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    // 堆外内存工具
    private static final Unsafe unsafe;
    static {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            unsafe = (Unsafe) f.get(null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // 环形缓冲区定义
    static class Buffer {
        final long[] fees; // 存储手续费（堆内数组，简化示例；实际可用堆外内存）
        final AtomicInteger size = new AtomicInteger(0); // 当前交易数
        final int capacity;

        Buffer(int capacity) {
            this.capacity = capacity;
            this.fees = new long[capacity];
        }

        // 写入交易（线程安全，返回是否成功）
        boolean write(long fee) {
            int currentSize = size.getAndIncrement();
            if (currentSize >= capacity) {
                size.decrementAndGet(); // 容量满，回滚
                return false;
            }
            fees[currentSize] = fee;
            return true;
        }

        // 读取当前所有交易（仅筛选时调用）
        long[] readAll() {
            int len = size.get();
            long[] data = new long[len];
            System.arraycopy(fees, 0, data, 0, len);
            return data;
        }

        // 重置缓冲区（切换后清空）
        void reset() {
            size.set(0);
        }
    }

    // 轻量小顶堆（复用之前的实现，增加合并功能）
    static class MiniHeap {
        private final long[] heap;
        private int size;

        public MiniHeap(int capacity) {
            heap = new long[capacity];
            size = 0;
        }

        public void insert(long value) {
            if (size < heap.length) {
                heap[size] = value;
                siftUp(size++);
            } else if (value > heap[0]) {
                heap[0] = value;
                siftDown(0);
            }
        }

        // 合并另一个数组到堆中
        public void merge(long[] array) {
            for (long val : array) {
                insert(val);
            }
        }

        // 上浮调整
        private void siftUp(int index) {
            long temp = heap[index];
            while (index > 0) {
                int parent = (index - 1) >>> 1; // 等价于(index-1)/2
                long parentVal = heap[parent];
                if (temp >= parentVal) break;
                heap[index] = parentVal;
                index = parent;
            }
            heap[index] = temp;
        }

        // 下沉调整
        private void siftDown(int index) {
            long temp = heap[index];
            int half = size >>> 1;
            while (index < half) {
                int left = (index << 1) + 1;
                int right = left + 1;
                int child = (right < size && heap[right] < heap[left]) ? right : left;
                long childVal = heap[child];
                if (temp <= childVal) break;
                heap[index] = childVal;
                index = child;
            }
            heap[index] = temp;
        }

        // 获取堆中所有元素（Top K结果）
        public long[] getElements() {
            long[] result = new long[size];
            System.arraycopy(heap, 0, result, 0, size);
            return result;
        }
    }

    public DynamicTopKProcessor() {
        // 启动定时筛选任务
        scheduler.scheduleAtFixedRate(this::filterTopK, 0, SCHEDULE_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    // 写入新交易（模拟每秒5000-10000笔）
    public void startWriting() {
        writerExecutor.submit(() -> {
            Random random = new Random();
            while (!Thread.currentThread().isInterrupted()) {
                // 每100ms写入500-1000笔（控制在每秒5000-10000笔）
                int batchSize = 500 + random.nextInt(500);
                for (int i = 0; i < batchSize; i++) {
                    long fee = random.nextLong(100000); // 随机手续费
                    // 写入当前激活的缓冲区，失败则忽略（环形队列满）
                    activeBuffer.get().write(fee);
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
    }

    // 执行筛选（核心逻辑）
    private void filterTopK() {
        long start = System.nanoTime();

        // 1. 切换缓冲区：将当前写入的缓冲区切换为筛选缓冲区，新写入切换到另一个
        Buffer currentBuffer = activeBuffer.get();
        Buffer newActiveBuffer = (currentBuffer == bufferA) ? bufferB : bufferA;
        activeBuffer.set(newActiveBuffer); // 新交易写入新缓冲区

        // 2. 读取当前缓冲区的新增交易（上一个间隔内写入的数据）
        long[] newTrades = currentBuffer.readAll();
        currentBuffer.reset(); // 重置，准备下一次切换

        // 3. 增量合并：用新增交易 + 上一次的Top K，筛选新的Top K
        long[] lastTop = globalTopCache.get();
        MiniHeap heap = new MiniHeap(TOP_K);
        heap.merge(lastTop);    // 合并历史Top K
        heap.merge(newTrades);  // 合并新增交易

        // 4. 更新全局缓存
        globalTopCache.set(heap.getElements());

        // 打印耗时
        long end = System.nanoTime();
        System.out.printf("筛选完成：新增%d笔，耗时%.2fms，当前Top5000最大值=%d%n",
                newTrades.length, (end - start) / 1_000_000.0, getMax(heap.getElements()));
    }

    private static long getMax(long[] array) {
        long max = array[0];
        for (long val : array) if (val > max) max = val;
        return max;
    }

    public void shutdown() {
        writerExecutor.shutdownNow();
        scheduler.shutdownNow();
    }

    public static void main(String[] args) throws InterruptedException {
        DynamicTopKProcessor processor = new DynamicTopKProcessor();
        processor.startWriting(); // 开始写入交易
        Thread.sleep(10_000); // 运行10秒
        processor.shutdown();
    }
}