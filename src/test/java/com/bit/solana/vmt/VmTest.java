package com.bit.solana.vmt;

import com.bit.solana.vm.SolanaVm;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static com.bit.solana.util.ClassUtil.readAndCompressClassFile;
import static com.bit.solana.util.ClassUtil.readClassFile;

@Slf4j
public class VmTest {
    public static void main(String[] args) throws Exception {
        String testContractPath = "F:\\workSpace2026\\blockchain\\solana_java\\src\\test\\java\\com\\bit\\solana\\vmt\\com\\bit\\solana\\vmt\\TransferContract.class"; // 替换为实际路径
        // 1. 读取并压缩class文件
        byte[] compressedBytes = readAndCompressClassFile(testContractPath);
        log.info("压缩后的字节长度：{}", compressedBytes.length);

        log.info("压缩后的字节长度：{}", compressedBytes);

        SolanaVm.BlockchainClassLoader classLoader = new SolanaVm.BlockchainClassLoader();
        Class<?> clazz = classLoader.loadClassFromCompressedBytes(compressedBytes);
        SolanaVm.ContractExecutor executor = new SolanaVm.ContractExecutor(clazz);

        // 4. 执行合约方法：初始化账户
        log.info("\n===== 初始化账户 =====");
        // 调用 initAccount(String address, long initialBalance)
        executor.execute("initAccount", "Alice", 1000L);
        executor.execute("initAccount", "Bob", 500L);

        // 5. 执行合约方法：查询初始余额
        log.info("\n===== 查询初始余额 =====");
        // 调用 getBalance(String address)
        long aliceBalance = (long) executor.execute("getBalance", "Alice");
        long bobBalance = (long) executor.execute("getBalance", "Bob");
        log.info("Alice初始余额：{}", aliceBalance);
        log.info("Bob初始余额：{}", bobBalance);


        // 6. 构建交易并签名
        log.info("\n===== 生成交易签名 =====");
        String from = "Alice";
        String to = "Bob";
        long amount = 300L;
        // 获取当前交易计数（用于生成txId）
        Field txCountField = clazz.getDeclaredField("transactionCount");
        txCountField.setAccessible(true); // 关键：允许访问私有变量
        long txCount = (long) txCountField.get(null); // 反射获取静态变量
        String txId = "TX" + (txCount + 1);

        String transactionData = String.format("from=%s&to=%s&amount=%d&txId=%s", from, to, amount, txId);
        // 调用 signData(String address, String data) 生成签名
        byte[] signature = (byte[]) executor.execute("signData", from, transactionData);
        log.info("交易签名生成成功，长度：{}字节", signature.length);

        // 7. 执行带签名的转账
        log.info("\n===== 执行带签名的转账 =====");
        // 调用 transferWithSignature(String from, String to, long amount, byte[] signature)
        Map<String, Object> txResult = (Map<String, Object>) executor.execute("transferWithSignature", from, to, amount, signature);
        log.info("转账结果：交易ID={}，状态={}", txResult.get("txId"), txResult.get("success"));

        // 8. 查询转账后余额
        log.info("\n===== 查询转账后余额 =====");
        aliceBalance = (long) executor.execute("getBalance", "Alice");
        bobBalance = (long) executor.execute("getBalance", "Bob");
        log.info("Alice转账后余额：{}", aliceBalance);
        log.info("Bob转账后余额：{}", bobBalance);

        // 9. 查询交易历史
        log.info("\n===== 查询Alice的交易历史 =====");
        // 调用 getTransactionHistory(String address)
        List<Map<String, Object>> history = (List<Map<String, Object>>) executor.execute("getTransactionHistory", "Alice");
        for (Map<String, Object> tx : history) {
            log.info("交易ID: {}, 从{}到{}, 金额: {}, 状态: {}",
                    tx.get("txId"), tx.get("from"), tx.get("to"),
                    tx.get("amount"), tx.get("success"));
        }

    }

}
