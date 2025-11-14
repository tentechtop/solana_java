package com.bit.solana.txpll2;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import com.bit.solana.mock.Transaction;
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
    // 全局交易计数（非精确，用于监控当前有效交易数）
    private final AtomicInteger currentValidSize = new AtomicInteger(0);
    // 新增：累计添加的交易总数（精确，不受删除影响）
    private final AtomicInteger totalAdded = new AtomicInteger(0);

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
        int size;          // 段内有效交易数（未被删除的）
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
     * 每次成功添加，累计添加数（totalAdded）+1，当前有效数（currentValidSize）+1
     */
    public boolean addTransaction(Transaction transaction) {
        int hash = Math.abs(transaction.getId().hashCode() % SEGMENT_COUNT);
        Segment segment = segments[hash];
        int capacity = segment.buffer.length;

        while (true) {
            int currentTail = segment.tail;
            int nextTail = (currentTail + 1) % capacity;

            if (segment.size >= capacity) {
                return false; // 段已满，添加失败
            }

            // CAS更新tail指针（无锁尝试写入）
            if (UNSAFE.compareAndSwapInt(segment, TAIL_OFFSET, currentTail, nextTail)) {
                // 成功获取位置，写入数据
                segment.lock.lock();
                try {
                    segment.buffer[currentTail] = transaction;
                    segment.deleted[currentTail] = false;
                    segment.size++;
                    currentValidSize.incrementAndGet(); // 当前有效数+1
                    totalAdded.incrementAndGet();       // 累计添加数+1（核心修改）
                } finally {
                    segment.lock.unlock();
                }
                return true;
            }

            Thread.yield(); // 并发冲突时让出CPU
        }
    }

    /**
     * 筛选并移除最高手续费的交易
     * 移除后，当前有效数（currentValidSize）会减少，累计添加数（totalAdded）不变
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

        // 3. 收集最终结果并标记删除
        List<Transaction> result = new ArrayList<>(Math.min(globalHeapSize, SELECTION_SIZE));
        Set<String> selectedIds = new HashSet<>(Math.min(globalHeapSize, SELECTION_SIZE));
        for (int i = 0; i < globalHeapSize; i++) {
            result.add(globalHeap[i]);
            selectedIds.add(globalHeap[i].getId());
        }

        // 4. 跨段标记删除选中的交易（会减少currentValidSize）
        markDeletedAcrossSegments(selectedIds);

        // 计算耗时并输出
        long costMs = (System.nanoTime() - startTime) / 1_000_000;
        System.out.printf("筛选耗时: %dms, 筛选数量: %d, 当前有效数: %d, 累计添加数: %d%n",
                costMs, result.size(), currentValidSize.get(), totalAdded.get());

        return result;
    }

    /**
     * 从单个段中筛选TopN交易
     */
    private List<Transaction> selectTopFromSegment(Segment segment) {
        segment.lock.lock();
        try {
            // 清理已标记删除的交易（减少currentValidSize）
            cleanDeletedInSegment(segment);
            if (segment.size == 0) {
                return Collections.emptyList();
            }

            // 段内筛选TopN
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

            // 转换为列表返回
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
     * 清理段内已标记删除的交易（减少当前有效数）
     */
    private void cleanDeletedInSegment(Segment segment) {
        int deletedCount = 0;
        // 批量清理已标记删除的交易
        while (segment.size > 0 && segment.deleted[segment.head]) {
            segment.buffer[segment.head] = null; // 帮助GC
            segment.head = (segment.head + 1) % segment.buffer.length;
            segment.size--;
            deletedCount++;
        }
        // 一次性减少全局有效数（减少锁竞争）
        if (deletedCount > 0) {
            currentValidSize.addAndGet(-deletedCount);
        }
    }

    /**
     * 跨所有段标记选中的交易为删除（仅标记，清理时才减少有效数）
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
                        segment.deleted[current] = true; // 仅标记删除，不立即减少size
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

    // 获取当前有效交易数（可能包含未清理的删除项，非精确）
    public int getCurrentValidSize() {
        return currentValidSize.get();
    }

    // 新增：获取累计添加的交易总数（精确，不受删除影响）
    public int getTotalAdded() {
        return totalAdded.get();
    }

    // 获取当前有效交易的精确值（遍历所有段统计，性能较低）
    public int getExactCurrentValidSize() {
        int exactValid = 0;
        for (Segment segment : segments) {
            segment.lock.lock();
            try {
                int current = segment.head;
                int remaining = segment.size;
                while (remaining > 0) {
                    if (!segment.deleted[current]) {
                        exactValid++;
                    }
                    current = (current + 1) % segment.buffer.length;
                    remaining--;
                }
            } finally {
                segment.lock.unlock();
            }
        }
        return exactValid;
    }


    /**
     * 程序启动初期，所有分段都是空的（size=0），tail 指针从 0 开始增长，此时 CAS 操作几乎不会失败（Thread.yield() 几乎不会触发）。
     * @param args
     * @throws InterruptedException
     */
    // 测试：验证累计添加数的精确性
    // 测试：每次固定添加1000笔交易后输出统计
    public static void main(String[] args) throws InterruptedException {
        HighPerformanceTransactionBuffer buffer = new HighPerformanceTransactionBuffer();
        Random random = new Random();

        // 多生产者线程（每次固定添加1000笔）
        int producerCount = 10;
        for (int p = 0; p < producerCount; p++) {
            new Thread(() -> {
                int batchSize = 1000; // 每次固定添加1000笔
                int cycleCount = 0;   // 记录循环次数

                while (!Thread.currentThread().isInterrupted()) {
                    int addedInBatch = 0; // 本批次成功添加的数量

                    // 单次循环添加1000笔
                    while (addedInBatch < batchSize) {
                        String id = UUID.randomUUID().toString();
                        long fee = random.nextLong(1000, 1_000_000);
                        if (buffer.addTransaction(new Transaction(id, fee))) {
                            addedInBatch++;
                        } else {
                            // 如果缓冲区满了，短暂等待后重试
                            try {
                                Thread.sleep(1);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                        }
                    }

                    // 每完成1000笔添加后输出统计
                    cycleCount++;
                    System.out.printf("第%d轮添加完成 | 累计添加%d笔 | 当前有效（精确）%d笔%n",
                            cycleCount,
                            buffer.getTotalAdded(),
                            buffer.getExactCurrentValidSize());

                    // 可根据需要添加延迟，控制添加速度
                    // Thread.sleep(100); // 例如每100ms添加1000笔
                }
            }).start();
        }

        // 消费者线程（可选，如需测试删除逻辑可开启）
        /*new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                buffer.selectAndRemoveTopTransactions();
                try {
                    Thread.sleep(400);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }).start();*/

        // 运行60秒后停止
        Thread.sleep(60_000);
        System.exit(0);
    }
}