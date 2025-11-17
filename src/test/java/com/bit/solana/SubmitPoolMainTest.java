package com.bit.solana;

import com.bit.solana.common.BlockHash;
import com.bit.solana.structure.account.AccountMeta;
import com.bit.solana.structure.tx.Instruction;
import com.bit.solana.structure.tx.Signature;
import com.bit.solana.structure.tx.Transaction;
import com.bit.solana.structure.tx.TransactionStatusResolver;
import com.bit.solana.txpool.impl.SubmitPoolImpl;
import com.bit.solana.util.Sha;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class SubmitPoolMainTest {

    private static SubmitPoolImpl submitPool;
    private static ExecutorService producerExecutor;
    private static ExecutorService consumerExecutor;
    private static volatile boolean running;
    private static final Random random = new Random();

    public static void main(String[] args) throws InterruptedException {
        // 初始化资源
        setup();

        // 启动生产者和消费者
        startWorkers();

        // 运行测试（持续30秒，可根据需要调整）
        Thread.sleep(30000 * 100);

        // 清理资源
        teardown();

/*
        submitPool = new SubmitPoolImpl();
        submitPool.init();

        Transaction tx = createRandomTransaction();
        submitPool.addTransaction(tx);
        String txIdStr = tx.getTxIdStr();
        Transaction transactionByTxId = submitPool.findTransactionByTxId(txIdStr);
        String statusString = TransactionStatusResolver.getStatusString(transactionByTxId);
        System.out.println("交易状态"+ statusString);

        System.out.println("查询到的叫i有"+transactionByTxId.toString());
*/


    }

    // 初始化提交池并填充10万笔初始交易
    private static void setup() throws InterruptedException {
        submitPool = new SubmitPoolImpl();
        submitPool.init();

        // 初始化10万笔初始交易
        int initialCount = 100_000;
        CountDownLatch initLatch = new CountDownLatch(1);
        Thread initThread = new Thread(() -> {
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
        });


        initThread.start();
        initLatch.await();



        // 初始化线程池
        producerExecutor = Executors.newFixedThreadPool(5);
        consumerExecutor = Executors.newSingleThreadExecutor();
        running = true;
        System.out.println("初始化完成，开始测试...");
    }

    // 启动生产者和消费者线程
    private static void startWorkers() {
        // 启动100个生产者线程
        for (int i = 0; i < 10; i++) {
            producerExecutor.submit(() -> {
                while (running) {
                    int count = 0;
                    // 每个生产者每秒提交1000笔
                    while (count < 1000 && running) {
                        Transaction tx = createRandomTransaction();
                            submitPool.addTransaction(tx);
                            count++;
                    }
                    // 控制速率：每秒提交1000笔后等待1秒
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            });
        }

        // 启动消费者线程（每500ms消费一次）
        consumerExecutor.submit(() -> {
            while (running) {
                List<Transaction> txs = submitPool.selectAndRemoveTopTransactions();
                System.out.printf("消费数量: %d, 剩余总数: %d%n",
                        txs.size(), submitPool.getTotalTransactionCount());
                try {
                    Thread.sleep(400);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
    }

    // 清理资源
    private static void teardown() {
        running = false;
        System.out.println("开始清理资源...");

        // 关闭线程池
        producerExecutor.shutdown();
        consumerExecutor.shutdown();
        try {
            if (!producerExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                producerExecutor.shutdownNow();
            }
            if (!consumerExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                consumerExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            producerExecutor.shutdownNow();
            consumerExecutor.shutdownNow();
        }

        System.out.printf("测试结束，最终交易数量: %d%n", submitPool.getTotalTransactionCount());
    }

    // 生成随机交易
    private static Transaction createRandomTransaction() {
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
        String abc = "abc" + i;
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
}