package com.bit.solana.api;

import com.bit.solana.database.DataBase;
import com.bit.solana.database.DbConfig;
import com.bit.solana.structure.dto.SmartContractDTO;
import com.bit.solana.vm.SolanaVm;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/smartContract")
public class SmartContractApi {

    @Autowired
    private DbConfig config;


    @PostMapping("/test")
    public void test(@RequestBody SmartContractDTO testVmDTO) throws Exception {
        byte[] compressedBytes = testVmDTO.getCompressedBytes();
        log.info("压缩后的字节长度：{}", compressedBytes.length);

        log.info("压缩后的字节长度：{}", compressedBytes);

        SolanaVm.BlockchainClassLoader classLoader = new SolanaVm.BlockchainClassLoader();
        Class<?> clazz = classLoader.loadClassFromCompressedBytes(compressedBytes);
        // 初始化执行器时可以指定Gas价格
        SolanaVm.ContractExecutorGas executor = new SolanaVm.ContractExecutorGas(clazz, 1L);// 100单位/ Gas


        executor.executeWithGas("initAccount", "Alice", 20000000L);
        executor.executeWithGas("initAccount", "Bob", 10000000L);


        log.info("\n===== 查询转账后余额 =====");
        Object[] result1 = executor.executeWithGas("getBalance", "Alice");
        log.info("Alice初始余额：{}  gas计费 {}", result1[0] ,result1[1]);
        Object[] result2 = executor.executeWithGas("getBalance", "Bob");
        log.info("Bob初始余额：{} gas计费 {}", result2[0], result2[1]);


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
        Object[] objects3 = executor.executeWithGas("signData", from, transactionData);
        log.info("交易签名生成成功，长度：{}字节", objects3[0]);


        // 7. 执行带签名的转账
        log.info("\n===== 执行带签名的转账 =====");
        // 调用 transferWithSignature(String from, String to, long amount, byte[] signature)
        Map<String, Object> txResult =  (Map<String, Object>) executor.executeWithGas("transferWithSignature", from, to, amount, objects3[0])[0];
        log.info("转账结果：交易ID={}，状态={}", txResult.get("txId"), txResult.get("success"));


        // 8. 查询转账后余额
        log.info("\n===== 查询转账后余额 =====");
        long aliceBalance = (long) executor.executeWithGas("getBalance", "Alice")[0];
        long bobBalance = (long) executor.executeWithGas("getBalance", "Bob")[0];
        log.info("Alice转账后余额：{}", aliceBalance);
        log.info("Bob转账后余额：{}", bobBalance);

        // 9. 查询交易历史
        log.info("\n===== 查询Alice的交易历史 =====");
        // 调用 getTransactionHistory(String address)
        Object[] objects = executor.executeWithGas("getTransactionHistory", "Alice");
        List<Map<String, Object>> history = (List<Map<String, Object>>)objects[0];
        for (Map<String, Object> tx : history) {
            log.info("交易ID: {}, 从{}到{}, 金额: {}, 状态: {}",
                    tx.get("txId"), tx.get("from"), tx.get("to"),
                    tx.get("amount"), tx.get("success"));
        }

        Object object = objects[1];
        log.info("gas计费 {}", object);
    }


    @PostMapping("/test1")
    public void test1(@RequestBody SmartContractDTO testVmDTO) throws Exception {
        byte[] compressedBytes = testVmDTO.getCompressedBytes();
        DataBase dataBase = config.getDataBase();

        SolanaVm.BlockchainClassLoader classLoader = new SolanaVm.BlockchainClassLoader();
        Class<?> contractClass = classLoader.loadClassFromCompressedBytes(compressedBytes);
        Constructor<?> constructor = contractClass.getConstructor(Object.class);
        Object contractInstance = constructor.newInstance(dataBase);
        SolanaVm.ContractExecutorGas executor = new SolanaVm.ContractExecutorGas(contractInstance, 1L);
        Object[] result = executor.executeWithGas("testInsertAndQuery"); // 调用无参方法




    }



}
