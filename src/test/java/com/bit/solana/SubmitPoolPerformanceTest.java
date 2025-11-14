package com.bit.solana;

import com.bit.solana.structure.tx.Transaction;
import com.bit.solana.structure.account.AccountMeta;
import com.bit.solana.structure.tx.Instruction;
import com.bit.solana.structure.tx.Signature;
import com.bit.solana.common.BlockHash;
import com.bit.solana.txpool.impl.SubmitPoolImpl;
import com.bit.solana.util.Sha;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 5)
@Measurement(iterations = 5, time = 10)
@Threads(Threads.MAX)
@Fork(1)
public class SubmitPoolPerformanceTest {

    private SubmitPoolImpl submitPool;
    private ExecutorService producerExecutor;
    private ExecutorService consumerExecutor;
    private volatile boolean running;
    private final Random random = new Random();

    // 初始化提交池并填充10万笔初始交易
    @Setup(Level.Trial)
    public void setup() {
        submitPool = new SubmitPoolImpl();
        submitPool.init();

        // 初始化10万笔初始交易
        int initialCount = 100_000;
        CountDownLatch initLatch = new CountDownLatch(1);
        new Thread(() -> {
            for (int i = 0; i < initialCount; i++) {
                Transaction tx = createRandomTransaction();
                while (!submitPool.addTransaction(tx)) {
                    // 如果添加失败（池满），短暂等待后重试
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                if (i % 10_000 == 0) {
                    System.out.printf("已初始化 %d/%d 笔交易%n", i, initialCount);
                }
            }
            initLatch.countDown();
        }).start();

        try {
            initLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("初始化交易池失败", e);
        }

        Assert.isTrue(submitPool.getTotalTransactionCount() == initialCount,
                "初始交易数量不正确");

        // 初始化线程池
        producerExecutor = Executors.newFixedThreadPool(5);
        consumerExecutor = Executors.newSingleThreadExecutor();
        running = true;
    }

    // 启动生产者和消费者线程
    @Setup(Level.Iteration)
    public void startWorkers() {
        for (int i = 0; i < 100; i++) {
            producerExecutor.submit(() -> {
                while (running) {
                    int count = 0;
                    // 每个生产者每秒提交1000笔
                    while (count < 1000) {
                        Transaction tx = createRandomTransaction();
                        if (submitPool.addTransaction(tx)) {
                            count++;
                        }
                    }
                }
            });
        }

        // 启动消费者，严格控制每秒执行一次消费
        consumerExecutor.submit(() -> {
            while (running) {
                List<Transaction> txs = submitPool.selectAndRemoveTopTransactions();
                System.out.println("消费数量: " + txs.size() + "剩余总数"+submitPool.getTotalTransactionCount());
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        });
    }

    // 基准测试方法 - 监控池状态
    @Benchmark
    public void monitorPool(Blackhole blackhole) {
        // 定期采样池状态
        blackhole.consume(submitPool.getTotalTransactionCount());
        blackhole.consume(submitPool.getTotalTransactionSize());

        // 每100ms采样一次
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // 清理资源
    @TearDown(Level.Trial)
    public void teardown() {
        running = false;
        producerExecutor.shutdown();
        consumerExecutor.shutdown();
        try {
            if (!producerExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                producerExecutor.shutdownNow();
            }
            if (!consumerExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                consumerExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            producerExecutor.shutdownNow();
            consumerExecutor.shutdownNow();
        }
        System.out.printf("测试结束，最终交易数量: %d%n", submitPool.getTotalTransactionCount());
    }

    // 生成随机交易
    private Transaction createRandomTransaction() {
        Transaction tx = new Transaction();

        // 生成随机签名
        List<Signature> signatures = new ArrayList<>();
        Signature sig = new Signature();
        byte[] sigBytes = new byte[64];
        random.nextBytes(sigBytes);
        sig.setValue(sigBytes);
        signatures.add(sig);
        tx.setSignatures(signatures);

        // 生成随机账户
        List<AccountMeta> accounts = new ArrayList<>();
        AccountMeta payer = new AccountMeta();
        byte[] pubKey = new byte[32];
        random.nextBytes(pubKey);
        payer.setPublicKey(pubKey);
        payer.setSigner(true);
        payer.setWritable(true);
        accounts.add(payer);
        tx.setAccounts(accounts);

        // 生成随机指令
        List<Instruction> instructions = new ArrayList<>();
        Instruction instr = new Instruction();
        instr.setProgramIdIndex(0);
        instr.setAccounts(List.of(0));
        byte[] data = new byte[32 + random.nextInt(1200)]; // 随机数据大小
        random.nextBytes(data);
        instr.setData(data);
        instructions.add(instr);
        tx.setInstructions(instructions);

        // 随机区块哈希
        int i = random.nextInt(1000);
        String abc = "abc"+i;
        byte[] bytes = Sha.applySHA256(abc.getBytes());
        BlockHash blockHash = new BlockHash(bytes);
        byte[] hash = new byte[32];
        random.nextBytes(hash);
        blockHash.setValue(hash);
        tx.setRecentBlockhash(blockHash);

        // 随机手续费
        tx.setFee(random.nextLong(100_000, 1_000_000));

        return tx;
    }

    // 运行测试
    public static void main(String[] args) throws RunnerException {
        Options options = new OptionsBuilder()
                .include(SubmitPoolPerformanceTest.class.getSimpleName())
                .build();
        new Runner(options).run();
    }
}