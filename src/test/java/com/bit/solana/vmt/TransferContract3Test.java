package com.bit.solana.vmt;

import com.bit.solana.database.DbConfig;
import com.bit.solana.database.rocksDb.RocksDb;
import com.bit.solana.database.rocksDb.TableEnum;
import com.bit.solana.vm.SolanaVm;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import static com.bit.solana.util.ClassUtil.readAndCompressClassFile;

@Slf4j
public class TransferContract3Test {
    public static void main(String[] args) throws Exception {
        RocksDb rocksDb = new RocksDb();
        DbConfig dbConfig = new DbConfig();
        dbConfig.setPath("/solana/db");
        rocksDb.createDatabase(dbConfig);


        String testContractPath = "F:\\workSpace2026\\blockchain\\solana_java\\src\\test\\java\\com\\bit\\solana\\vmt\\com\\bit\\solana\\vmt\\TransferContract3.class"; // 替换为实际路径
        // 1. 读取并压缩class文件
        byte[] compressedBytes = readAndCompressClassFile(testContractPath);
        log.info("压缩后的字节长度：{}", compressedBytes.length);
        log.info("压缩后的字节内容：{}", compressedBytes);
        SolanaVm.BlockchainClassLoader classLoader = new SolanaVm.BlockchainClassLoader();
        Class<?> contractClass = classLoader.loadClassFromCompressedBytes(compressedBytes);
        Constructor<?> constructor = contractClass.getConstructor(Object.class);
        Object contractInstance = constructor.newInstance(rocksDb);
        SolanaVm.ContractExecutorGas executor = new SolanaVm.ContractExecutorGas(contractInstance, 1L);
        Object[] result = executor.executeWithGas("testInsertAndQuery"); // 调用无参方法








    }
}
