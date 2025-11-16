package com.bit.solana.vmt;

import com.bit.solana.vm.SolanaVm;
import lombok.extern.slf4j.Slf4j;

import static com.bit.solana.util.ClassUtil.readAndCompressClassFile;

@Slf4j
public class VmTestBack {
    public static void main(String[] args) throws Exception {
        String testContractPath = "F:\\workSpace2026\\blockchain\\solana_java\\src\\test\\java\\com\\bit\\solana\\vmt\\com\\bit\\solana\\vmt\\TransferContract.class"; // 替换为实际路径
        // 1. 读取并压缩class文件
        byte[] compressedBytes = readAndCompressClassFile(testContractPath);
        log.info("压缩后的字节长度：{}", compressedBytes.length);

        SolanaVm.BlockchainClassLoader classLoader = new SolanaVm.BlockchainClassLoader();
        Class<?> clazz1 = classLoader.loadClassFromCompressedBytes(compressedBytes);
        SolanaVm.ContractExecutor executor = new SolanaVm.ContractExecutor(clazz1);

        // 3. 执行方法：直接传方法名和参数，无需手动处理反射细节
        executor.execute("initAccount", "Alice", 1000L);
        log.info("初始化Alice账户成功");

        Object transferResult = executor.execute("transfer", "Alice", "Bob", 500L);
        log.info("转账结果：{}", transferResult);

    }

}
