package com.bit.solana.mock;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

public class TransactionBuffer {
    private static final int MAX_CAPACITY = 1_000_000;
    private static final int SELECTION_SIZE = 5_000;

    private final Transaction[] buffer;
    private int head = 0;
    private int tail = 0;
    private int size = 0;
    private final boolean[] deleted;

    // 改用数组+手动维护堆结构提升性能
    private final Transaction[] topHeap;
    private int heapSize = 0;

    private final ReentrantLock lock = new ReentrantLock();

    public TransactionBuffer() {
        buffer = new Transaction[MAX_CAPACITY];
        deleted = new boolean[MAX_CAPACITY];
        topHeap = new Transaction[SELECTION_SIZE];
    }

    public boolean addTransaction(Transaction transaction) {
        lock.lock();
        try {
            if (size >= MAX_CAPACITY) {
                return false;
            }

            buffer[tail] = transaction;
            deleted[tail] = false;
            tail = (tail + 1) % MAX_CAPACITY;
            size++;
            return true;
        } finally {
            lock.unlock();
        }
    }

    public List<Transaction> selectAndRemoveTopTransactions() {
        lock.lock();
        try {
            long startTime = System.nanoTime(); // 改用纳秒级计时

            if (size == 0) {
                return Collections.emptyList();
            }

            cleanDeletedElements();
            if (size == 0) {
                return Collections.emptyList();
            }

            // 重建小顶堆
            heapSize = 0;
            int current = head;
            int remaining = size;

            while (remaining > 0) {
                if (!deleted[current]) {
                    Transaction t = buffer[current];
                    if (heapSize < SELECTION_SIZE) {
                        topHeap[heapSize] = t;
                        siftUp(heapSize);
                        heapSize++;
                    } else if (t.getFee() > topHeap[0].getFee()) {
                        topHeap[0] = t;
                        siftDown(0);
                    }
                }
                current = (current + 1) % MAX_CAPACITY;
                remaining--;
            }

            // 收集结果
            List<Transaction> result = new ArrayList<>(Math.min(heapSize, SELECTION_SIZE));
            Set<String> selectedIds = new HashSet<>(Math.min(heapSize, SELECTION_SIZE));

            for (int i = 0; i < heapSize; i++) {
                result.add(topHeap[i]);
                selectedIds.add(topHeap[i].getId());
            }

            // 标记删除
            markTransactionsAsDeleted(selectedIds);

            // 计算实际耗时(转换为毫秒)
            long endTime = System.nanoTime();
            long costMs = (endTime - startTime) / 1_000_000;
            System.out.printf("实际筛选耗时: %dms, 筛选数量: %d, 剩余数量: %d%n",
                    costMs, result.size(), size);

            return result;
        } finally {
            lock.unlock();
        }
    }

    // 小顶堆上浮操作
    private void siftUp(int index) {
        while (index > 0) {
            int parent = (index - 1) >>> 1;
            if (topHeap[index].getFee() >= topHeap[parent].getFee()) {
                break;
            }
            swap(index, parent);
            index = parent;
        }
    }

    // 小顶堆下沉操作
    private void siftDown(int index) {
        int half = heapSize >>> 1;
        while (index < half) {
            int left = (index << 1) + 1;
            int right = left + 1;
            int smallest = left;

            if (right < heapSize && topHeap[right].getFee() < topHeap[left].getFee()) {
                smallest = right;
            }
            if (topHeap[index].getFee() <= topHeap[smallest].getFee()) {
                break;
            }
            swap(index, smallest);
            index = smallest;
        }
    }

    private void swap(int i, int j) {
        Transaction temp = topHeap[i];
        topHeap[i] = topHeap[j];
        topHeap[j] = temp;
    }

    private void cleanDeletedElements() {
        while (size > 0 && deleted[head]) {
            buffer[head] = null;
            head = (head + 1) % MAX_CAPACITY;
            size--;
        }
    }

    private void markTransactionsAsDeleted(Set<String> selectedIds) {
        if (selectedIds.isEmpty()) return;

        int current = head;
        int remaining = size;
        int marked = 0;

        while (remaining > 0 && !selectedIds.isEmpty()) {
            if (!deleted[current] && selectedIds.contains(buffer[current].getId())) {
                deleted[current] = true;
                selectedIds.remove(buffer[current].getId());
                marked++;
            }
            current = (current + 1) % MAX_CAPACITY;
            remaining--;
        }
    }

    public int getSize() {
        lock.lock();
        try {
            return size;
        } finally {
            lock.unlock();
        }
    }

    public static void main(String[] args) throws InterruptedException {
        TransactionBuffer buffer = new TransactionBuffer();
        Random random = new Random();

        // 生产者线程 - 控制实际添加速率
        Thread producer = new Thread(() -> {
            long lastTime = System.currentTimeMillis();
            int count = 0;

            while (!Thread.currentThread().isInterrupted()) {
                String id = UUID.randomUUID().toString();
                long fee = random.nextLong(1000, 1000000);
                if (buffer.addTransaction(new Transaction(id, fee))) {
                    count++;
                }

                // 控制每秒最多添加10000笔
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastTime >= 1000) {
                    System.out.printf("添加交易: %d笔/秒%n", count);
                    count = 0;
                    lastTime = currentTime;
                }

                // 微小延迟控制速率
                try {
                    Thread.sleep(0, 100); // 100纳秒延迟
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });
        producer.start();

        // 消费者线程 - 每400ms筛选一次
        Thread consumer = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                buffer.selectAndRemoveTopTransactions();
                try {
                    Thread.sleep(400);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });
        consumer.start();

        // 运行30秒后停止
        Thread.sleep(30000);
        producer.interrupt();
        consumer.interrupt();
    }
}