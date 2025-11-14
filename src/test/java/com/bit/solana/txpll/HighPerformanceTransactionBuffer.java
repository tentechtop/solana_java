package com.bit.solana.txpll;

import java.util.*;
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
    // 全局交易计数（非精确，用于监控）
    private final AtomicInteger totalSize = new AtomicInteger(0);

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
     * 高并发添加交易（支持10万+/秒）
     * 基于交易ID哈希分片，使用CAS减少锁竞争
     */
    public boolean addTransaction(Transaction transaction) {
        // 基于交易ID哈希分配到具体段（分散锁竞争）
        int hash = Math.abs(transaction.getId().hashCode() % SEGMENT_COUNT);
        Segment segment = segments[hash];
        int capacity = segment.buffer.length;

        while (true) {
            int currentTail = segment.tail;
            int nextTail = (currentTail + 1) % capacity;

            // 检查段是否已满（通过size判断，避免ABA问题）
            if (segment.size >= capacity) {
                System.out.println("已经满了");
                return false; // 段已满
            }

            // CAS更新tail指针（无锁尝试写入）
            if (UNSAFE.compareAndSwapInt(segment, TAIL_OFFSET, currentTail, nextTail)) {
                // 成功获取位置，写入数据（加锁保证原子性）
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

            // CAS失败，说明有并发写入，重试
            Thread.yield(); // 让出CPU，减少忙等
        }
    }

    /**
     * 筛选并移除最高手续费的交易（400ms一次，控制在30ms内）
     * 分段并行筛选，最后合并结果
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

        // 4. 跨段标记删除选中的交易
        markDeletedAcrossSegments(selectedIds);

        // 计算耗时并输出
        long costMs = (System.nanoTime() - startTime) / 1_000_000;
        System.out.printf("筛选耗时: %dms, 筛选数量: %d, 剩余总数: %d%n",
                costMs, result.size(), totalSize.get());

        return result;
    }

    /**
     * 从单个段中筛选TopN交易
     */
    private List<Transaction> selectTopFromSegment(Segment segment) {
        segment.lock.lock();
        try {
            // 清理已标记删除的交易（惰性删除）
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
     * 清理段内已标记删除的交易
     */
    private void cleanDeletedInSegment(Segment segment) {
        while (segment.size > 0 && segment.deleted[segment.head]) {
            segment.buffer[segment.head] = null; // 帮助GC
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

    // 小顶堆上浮操作（通用方法）
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

    // 小顶堆下沉操作（通用方法）
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

    /**
     * 获取当前缓冲区中有效交易的精确总数（会加锁所有分段，影响性能）
     */
    public int getExactTotalSize() {
        int exactTotal = 0;
        // 遍历所有分段，加锁后统计有效交易数
        for (Segment segment : segments) {
            segment.lock.lock();
            try {
                // 段内有效交易数 = size（已排除清理过的删除项）
                // 注意：此时可能存在已标记删除但未清理的交易，但segment.size已扣除它们
                // （因为size在markDeleted时不会立即减少，而是在cleanDeletedInSegment时减少）
                // 因此需要重新计算当前段内真正有效的交易数
                int segmentValid = 0;
                int current = segment.head;
                int remaining = segment.size; // 基于当前未清理的size遍历
                while (remaining > 0) {
                    if (!segment.deleted[current]) { // 未被标记删除的才是有效交易
                        segmentValid++;
                    }
                    current = (current + 1) % segment.buffer.length;
                    remaining--;
                }
                exactTotal += segmentValid;
            } finally {
                segment.lock.unlock();
            }
        }
        return exactTotal;
    }

    // 测试：支持每秒1万-10万笔交易
    public static void main(String[] args) throws InterruptedException {
        HighPerformanceTransactionBuffer buffer = new HighPerformanceTransactionBuffer();
        Random random = new Random();

        // 多生产者线程（模拟每秒1万-10万笔）
        int producerCount = 8; // 8个生产者线程
        for (int p = 0; p < producerCount; p++) {
            new Thread(() -> {
                long lastTime = System.currentTimeMillis();
                int count = 0;

                while (!Thread.currentThread().isInterrupted()) {
                    String id = UUID.randomUUID().toString();
                    long fee = random.nextLong(1000, 1_000_000); // 手续费随机
                    if (buffer.addTransaction(new Transaction(id, fee))) {
                        count++;
                    }

                    // 控制速率在1万-10万/秒（每个线程1.25万-12.5万/秒）
                    long currentTime = System.currentTimeMillis();
                    if (currentTime - lastTime >= 1000) {
      /*                  System.out.printf("线程[%s]添加: %d笔/秒, 总累计: %d%n",
                                Thread.currentThread().getId(), count, buffer.getTotalSize());
*/
                        System.out.printf("线程[%s]添加: %d笔/秒, 总累计: %d%n",
                                Thread.currentThread().getId(), count, buffer.getExactTotalSize());


                        count = 0;
                        lastTime = currentTime;
                    }

                    // 动态调整延迟，控制总速率
                    if (buffer.getTotalSize() > MAX_CAPACITY * 0.8) {
                        try {
                            Thread.sleep(1); // 缓冲池快满时减速
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
            }).start();
        }

/*        // 消费者线程（每400ms筛选一次）
        new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {

                System.out.printf(" 总累计: %d%n", buffer.getExactTotalSize());


                List<Transaction> transactions = buffer.selectAndRemoveTopTransactions();

                Set<Transaction> uniqueTransactions = new HashSet<>(transactions);
                boolean hasDuplicates = uniqueTransactions.size() < transactions.size();

                if (hasDuplicates) {
                    System.out.println("存在重复交易");
                } else {
                    System.out.println("没有重复交易");
                }

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