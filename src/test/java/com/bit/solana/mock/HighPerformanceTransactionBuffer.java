package com.bit.solana.mock;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import sun.misc.Unsafe;
import java.lang.reflect.Field;

public class HighPerformanceTransactionBuffer {
    // 配置参数
    private static final int MAX_CAPACITY = 1_000_000; // 总容量
    private static final int SELECTION_SIZE = 5_000;   // 每次筛选数量
    private static final int SEGMENT_COUNT = 16;       // 分段数量（2的幂次，便于哈希）
    private static final int SEGMENT_CAPACITY = MAX_CAPACITY / SEGMENT_COUNT; // 每段容量

    // 分段存储结构
    private final Segment[] segments;
    // 全局交易计数（非精确，用于监控）
    private final AtomicInteger totalSize = new AtomicInteger(0);
    // 记录已提取的交易ID（用于重复检测）
    private final Set<String> extractedTransactionIds = ConcurrentHashMap.newKeySet();

    // Unsafe实例用于CAS操作（提升写入性能）
    private static final Unsafe UNSAFE;
    private static final long TAIL_OFFSET;

    static {
        try {
            // 反射获取Unsafe实例
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            UNSAFE = (Unsafe) field.get(null);
            // 获取Segment类中tail字段的内存偏移量
            TAIL_OFFSET = UNSAFE.objectFieldOffset(Segment.class.getDeclaredField("tail"));
        } catch (Exception e) {
            throw new RuntimeException("初始化Unsafe失败", e);
        }
    }

    // 分段内部类（每个段独立锁和存储）
    private static class Segment {
        final Transaction[] buffer;
        final boolean[] deleted;
        volatile int tail; // 写入指针（volatile保证可见性）
        int head;          // 读取指针（仅当前段锁保护）
        int size;          // 段内有效交易数
        final ReentrantLock lock = new ReentrantLock();

        Segment(int capacity) {
            this.buffer = new Transaction[capacity];
            this.deleted = new boolean[capacity];
        }
    }

    public HighPerformanceTransactionBuffer() {
        segments = new Segment[SEGMENT_COUNT];
        for (int i = 0; i < SEGMENT_COUNT; i++) {
            segments[i] = new Segment(SEGMENT_CAPACITY);
        }
    }

    /**
     * 高并发添加交易
     */
    public boolean addTransaction(Transaction transaction) {
        int hash = Math.abs(transaction.getId().hashCode() % SEGMENT_COUNT);
        Segment segment = segments[hash];
        int capacity = segment.buffer.length;

        while (true) {
            int currentTail = segment.tail;
            int nextTail = (currentTail + 1) % capacity;

            if (segment.size >= capacity) {
                return false; // 段已满
            }

            if (UNSAFE.compareAndSwapInt(segment, TAIL_OFFSET, currentTail, nextTail)) {
                segment.lock.lock();
                try {
                    segment.buffer[currentTail] = transaction;
                    segment.deleted[currentTail] = false;
                    segment.size++;
                    totalSize.incrementAndGet();
                } finally {
                    segment.lock.unlock();
                }
                return true;
            }

            Thread.yield();
        }
    }

    /**
     * 筛选并移除最高手续费的交易，添加重复检测和准确性验证
     */
    public List<Transaction> selectAndRemoveTopTransactions() {
        long startTime = System.nanoTime();

        // 1. 分段筛选TopN
        List<Transaction>[] segmentTops = new List[SEGMENT_COUNT];
        for (int i = 0; i < SEGMENT_COUNT; i++) {
            segmentTops[i] = selectTopFromSegment(segments[i]);
        }

        // 2. 合并所有段的TopN，得到全局TopN
        Transaction[] globalHeap = new Transaction[SELECTION_SIZE];
        int globalHeapSize = 0;

        for (List<Transaction> segmentTop : segmentTops) {
            for (Transaction t : segmentTop) {
                if (globalHeapSize < SELECTION_SIZE) {
                    globalHeap[globalHeapSize] = t;
                    siftUp(globalHeap, globalHeapSize);
                    globalHeapSize++;
                } else if (t.getFee() > globalHeap[0].getFee()) {
                    globalHeap[0] = t;
                    siftDown(globalHeap, 0, globalHeapSize);
                }
            }
        }

        // 3. 收集最终结果并标记删除（添加校验逻辑）
        List<Transaction> result = new ArrayList<>(Math.min(globalHeapSize, SELECTION_SIZE));
        Set<String> selectedIds = new HashSet<>(Math.min(globalHeapSize, SELECTION_SIZE));
        int duplicateCount = 0; // 重复提取的交易数量
        int invalidCount = 0;   // 已被删除或不存在的交易数量

        for (int i = 0; i < globalHeapSize; i++) {
            Transaction t = globalHeap[i];
            String txId = t.getId();

            // 检测是否重复提取
            if (extractedTransactionIds.contains(txId)) {
                duplicateCount++;
                continue;
            }

            // 验证交易是否仍有效（未被删除且存在于缓冲区中）
            if (!isTransactionValid(txId)) {
                invalidCount++;
                continue;
            }

            result.add(t);
            selectedIds.add(txId);
            extractedTransactionIds.add(txId); // 记录已提取
        }

        // 4. 跨段标记删除选中的交易
        markDeletedAcrossSegments(selectedIds);

        // 计算耗时并输出校验结果
        long costMs = (System.nanoTime() - startTime) / 1_000_000;
        System.out.printf(
                "筛选耗时: %dms, 筛选数量: %d, 重复交易: %d, 无效交易: %d, 剩余总数: %d%n",
                costMs, result.size(), duplicateCount, invalidCount, totalSize.get()
        );

        return result;
    }

    /**
     * 验证交易是否有效（存在于缓冲区且未被删除）
     */
    private boolean isTransactionValid(String txId) {
        int hash = Math.abs(txId.hashCode() % SEGMENT_COUNT);
        Segment segment = segments[hash];
        segment.lock.lock();
        try {
            int current = segment.head;
            int remaining = segment.size;
            while (remaining > 0) {
                if (!segment.deleted[current] && segment.buffer[current].getId().equals(txId)) {
                    return true; // 交易存在且未被删除
                }
                current = (current + 1) % segment.buffer.length;
                remaining--;
            }
            return false; // 交易不存在或已被删除
        } finally {
            segment.lock.unlock();
        }
    }

    /**
     * 从单个段中筛选TopN交易
     */
    private List<Transaction> selectTopFromSegment(Segment segment) {
        segment.lock.lock();
        try {
            cleanDeletedInSegment(segment);
            if (segment.size == 0) {
                return Collections.emptyList();
            }

            Transaction[] heap = new Transaction[SELECTION_SIZE];
            int heapSize = 0;
            int current = segment.head;
            int remaining = segment.size;

            while (remaining > 0) {
                if (!segment.deleted[current]) {
                    Transaction t = segment.buffer[current];
                    if (heapSize < SELECTION_SIZE) {
                        heap[heapSize] = t;
                        siftUp(heap, heapSize);
                        heapSize++;
                    } else if (t.getFee() > heap[0].getFee()) {
                        heap[0] = t;
                        siftDown(heap, 0, heapSize);
                    }
                }
                current = (current + 1) % segment.buffer.length;
                remaining--;
            }

            List<Transaction> result = new ArrayList<>(heapSize);
            for (int i = 0; i < heapSize; i++) {
                result.add(heap[i]);
            }
            return result;
        } finally {
            segment.lock.unlock();
        }
    }

    /**
     * 清理段内已标记删除的交易
     */
    private void cleanDeletedInSegment(Segment segment) {
        while (segment.size > 0 && segment.deleted[segment.head]) {
            segment.buffer[segment.head] = null;
            segment.head = (segment.head + 1) % segment.buffer.length;
            segment.size--;
            totalSize.decrementAndGet();
        }
    }

    /**
     * 跨所有段标记选中的交易为删除
     */
    private void markDeletedAcrossSegments(Set<String> selectedIds) {
        if (selectedIds.isEmpty()) return;

        for (Segment segment : segments) {
            segment.lock.lock();
            try {
                int current = segment.head;
                int remaining = segment.size;

                while (remaining > 0 && !selectedIds.isEmpty()) {
                    if (!segment.deleted[current] && selectedIds.contains(segment.buffer[current].getId())) {
                        segment.deleted[current] = true;
                        selectedIds.remove(segment.buffer[current].getId());
                    }
                    current = (current + 1) % segment.buffer.length;
                    remaining--;
                }
            } finally {
                segment.lock.unlock();
            }
        }
    }

    // 小顶堆上浮操作
    private void siftUp(Transaction[] heap, int index) {
        while (index > 0) {
            int parent = (index - 1) >>> 1;
            if (heap[index].getFee() >= heap[parent].getFee()) {
                break;
            }
            swap(heap, index, parent);
            index = parent;
        }
    }

    // 小顶堆下沉操作
    private void siftDown(Transaction[] heap, int index, int heapSize) {
        int half = heapSize >>> 1;
        while (index < half) {
            int left = (index << 1) + 1;
            int right = left + 1;
            int smallest = left;

            if (right < heapSize && heap[right].getFee() < heap[left].getFee()) {
                smallest = right;
            }
            if (heap[index].getFee() <= heap[smallest].getFee()) {
                break;
            }
            swap(heap, index, smallest);
            index = smallest;
        }
    }

    private void swap(Transaction[] heap, int i, int j) {
        Transaction temp = heap[i];
        heap[i] = heap[j];
        heap[j] = temp;
    }

    public int getTotalSize() {
        return totalSize.get();
    }

    // 交易类（补充定义）
    public static class Transaction {
        private final String id;
        private final long fee;

        public Transaction(String id, long fee) {
            this.id = id;
            this.fee = fee;
        }

        public String getId() {
            return id;
        }

        public long getFee() {
            return fee;
        }
    }

    // 测试方法
    public static void main(String[] args) throws InterruptedException {
        HighPerformanceTransactionBuffer buffer = new HighPerformanceTransactionBuffer();
        Random random = new Random();

        // 多生产者线程
        int producerCount = 8;
        for (int p = 0; p < producerCount; p++) {
            new Thread(() -> {
                long lastTime = System.currentTimeMillis();
                int count = 0;

                while (!Thread.currentThread().isInterrupted()) {
                    String id = UUID.randomUUID().toString();
                    long fee = random.nextLong(1000, 1_000_000);
                    if (buffer.addTransaction(new Transaction(id, fee))) {
                        count++;
                    }

                    long currentTime = System.currentTimeMillis();
                    if (currentTime - lastTime >= 1000) {
                        System.out.printf("线程[%s]添加: %d笔/秒, 总累计: %d%n",
                                Thread.currentThread().getId(), count, buffer.getTotalSize());
                        count = 0;
                        lastTime = currentTime;
                    }

                    if (buffer.getTotalSize() > MAX_CAPACITY * 0.8) {
                        try {
                            Thread.sleep(1);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
            }).start();
        }

        // 消费者线程
        new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                buffer.selectAndRemoveTopTransactions();
                try {
                    Thread.sleep(400);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }).start();

        // 运行60秒后停止
        Thread.sleep(60_000);
        System.exit(0);
    }
}