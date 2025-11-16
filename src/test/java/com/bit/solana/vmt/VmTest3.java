package com.bit.solana.vmt;

import com.bit.solana.vm.SolanaVm;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static com.bit.solana.util.ClassUtil.readAndCompressClassFile;

@Slf4j
public class VmTest3 {
    public static void main(String[] args) throws Exception {
        String testContractPath = "F:\\workSpace2026\\blockchain\\solana_java\\src\\test\\java\\com\\bit\\solana\\vmt\\com\\bit\\solana\\vmt\\CryptoContract.class"; // 替换为实际路径
        // 1. 读取并压缩class文件
        byte[] compressedBytes = readAndCompressClassFile(testContractPath);
        log.info("压缩后的字节长度：{}", compressedBytes.length);
        log.info("压缩后的字节长度：{}", compressedBytes);
        SolanaVm.BlockchainClassLoader classLoader = new SolanaVm.BlockchainClassLoader();
        Class<?> clazz = classLoader.loadClassFromCompressedBytes(compressedBytes);
        // 初始化执行器时可以指定Gas价格
        SolanaVm.ContractExecutorGas executor = new SolanaVm.ContractExecutorGas(clazz, 1L);// 1单位/ Gas


        executor.executeWithGas("createAccount", "Alice");
        executor.executeWithGas("createAccount", "Bob");

        Object[] objects = executor.executeWithGas("mine", "Alice");
        log.info("挖矿结果：{}  gas计费 {}", objects[0], objects[1]);
        long transferAmount = 30;
        Object[] objects1 = executor.executeWithGas("generateTxId", "Alice", "Bob", transferAmount);
        log.info("交易ID：{}  gas计费 {}", objects1[0], objects1[1]);
        String txId = (String) objects1[0];
        Map<String, Object> tx = new HashMap<>();
        tx.put("txId", txId);
        tx.put("from", "Alice");
        tx.put("to", "Bob");
        tx.put("amount", transferAmount);
        tx.put("timestamp", System.currentTimeMillis()); // 时间戳仅在此处设置

        Object[] objects2 = executor.executeWithGas("getBalance", "Alice");
        log.info("Alice的余额：{}  gas计费 {}", objects2[0], objects2[1]);

        Class<?>[] paramTypes = new Class[]{String.class, Map.class};
        Object[] objects3 = executor.executeByName("signTransaction", "Alice", tx);
        log.info("签名后的交易：{}  gas计费 {}", objects3[0], objects3[1]);

    }

}
