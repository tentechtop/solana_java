package com.bit.solana.mock;

import sun.misc.Unsafe;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

public class HighPerformanceTopK {
    // 常量定义
    private static final int TOTAL_TRADES = 1_000_000; // 总交易数
    private static final int TOP_K = 5000;             // 需筛选的Top数
    private static final int CPU_CORES = Runtime.getRuntime().availableProcessors(); // 核心数
    private static final int SLICE_SIZE = TOTAL_TRADES / CPU_CORES; // 每片数据量

    // 堆外内存存储交易手续费（避免GC）
    private final long[] fees;
    private final Unsafe unsafe;
    private final long baseAddress; // 堆外内存起始地址

    public HighPerformanceTopK() throws Exception {
        // 初始化Unsafe（用于堆外内存操作）
        Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        unsafe = (Unsafe) unsafeField.get(null);

        // 分配堆外内存（8字节/long，共100万条）
        long memorySize = (long) TOTAL_TRADES * 8;
        baseAddress = unsafe.allocateMemory(memorySize);
        unsafe.setMemory(baseAddress, memorySize, (byte) 0); // 初始化内存

        // 模拟填充随机手续费（0-100000）
        fees = new long[TOTAL_TRADES]; // 本地数组仅用于初始化，实际筛选用堆外内存
        for (int i = 0; i < TOTAL_TRADES; i++) {
            fees[i] = (long) (Math.random() * 100000);
            unsafe.putLong(baseAddress + i * 8, fees[i]); // 写入堆外内存
        }
    }

    // 轻量小顶堆实现（仅处理long类型）
    static class MiniHeap {
        private final long[] heap;
        private int size;

        public MiniHeap(int capacity) {
            heap = new long[capacity];
            size = 0;
        }

        // 插入元素，超过容量时替换堆顶（最小元素）
        public void insert(long value) {
            if (size < heap.length) {
                heap[size] = value;
                siftUp(size++);
            } else if (value > heap[0]) {
                heap[0] = value;
                siftDown(0);
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

    // 分片处理线程
    class SliceProcessor implements Callable<long[]> {
        private final int start;
        private final int end;

        public SliceProcessor(int start, int end) {
            this.start = start;
            this.end = end;
        }

        @Override
        public long[] call() {
            MiniHeap heap = new MiniHeap(TOP_K);
            // 从堆外内存读取数据（连续内存+直接地址访问，速度极快）
            for (int i = start; i < end; i++) {
                long fee = unsafe.getLong(baseAddress + i * 8);
                heap.insert(fee);
            }
            return heap.getElements();
        }
    }

    // 执行筛选并返回Top K结果
    public long[] getTopK() throws InterruptedException, ExecutionException {
        // 线程池（核心数=CPU核心数，避免线程切换开销）
        ExecutorService executor = Executors.newFixedThreadPool(CPU_CORES);
        List<Future<long[]>> futures = new ArrayList<>(CPU_CORES);

        // 提交分片任务
        for (int i = 0; i < CPU_CORES; i++) {
            int start = i * SLICE_SIZE;
            int end = (i == CPU_CORES - 1) ? TOTAL_TRADES : start + SLICE_SIZE;
            futures.add(executor.submit(new SliceProcessor(start, end)));
        }

        // 合并分片结果
        MiniHeap globalHeap = new MiniHeap(TOP_K);
        for (Future<long[]> future : futures) {
            long[] sliceTop = future.get();
            for (long fee : sliceTop) {
                globalHeap.insert(fee);
            }
        }

        executor.shutdown();
        return globalHeap.getElements();
    }

    // 释放堆外内存
    public void clean() {
        unsafe.freeMemory(baseAddress);
    }

    // 测试方法
    public static void main(String[] args) throws Exception {
        HighPerformanceTopK processor = new HighPerformanceTopK();

        // 预热（触发JIT编译）
        for (int i = 0; i < 10; i++) {
            processor.getTopK();
        }

        // 正式测试
        long start = System.nanoTime();
        long[] topK = processor.getTopK();
        long end = System.nanoTime();

        System.out.println("筛选耗时：" + (end - start) / 1_000_000.0 + " ms");
        System.out.println("Top 5000中最大值（验证正确性）：" + getMax(topK));

        processor.clean();
    }

    private static long getMax(long[] array) {
        long max = array[0];
        for (long val : array) {
            if (val > max) max = val;
        }
        return max;
    }
}