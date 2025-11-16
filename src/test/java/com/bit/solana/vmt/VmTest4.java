package com.bit.solana.vmt;

import com.bit.solana.vm.SolanaVm;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.bit.solana.util.ClassUtil.readAndCompressClassFile;


@Slf4j
public class VmTest4 {
    public static void main(String[] args) throws Exception {
        String testContractPath = "F:\\workSpace2026\\blockchain\\solana_java\\src\\test\\java\\com\\bit\\solana\\vmt\\com\\bit\\solana\\vmt\\RentalContract.class"; // 替换为实际路径
        // 1. 读取并压缩class文件
        byte[] compressedBytes = readAndCompressClassFile(testContractPath);
        log.info("压缩后的字节长度：{}", compressedBytes.length);
        log.info("压缩后的字节内容：{}", compressedBytes);
        SolanaVm.BlockchainClassLoader classLoader = new SolanaVm.BlockchainClassLoader();
        Class<?> clazz = classLoader.loadClassFromCompressedBytes(compressedBytes);
        // 初始化执行器时可以指定Gas价格
        SolanaVm.ContractExecutorGas executor = new SolanaVm.ContractExecutorGas(clazz, 1L);// 1单位/ Gas










    }

}
