package com.bit.solana;

import com.bit.solana.database.DataBase;
import com.bit.solana.config.SystemConfig;
import com.bit.solana.database.KeyValueHandler;
import com.bit.solana.database.rocksDb.TableEnum;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Arrays;

@Slf4j
@SpringBootTest
public class DbTest {

    @Autowired
    private SystemConfig dbConfig; // 变量名规范：小写开头

    // 测试数据常量（避免硬编码重复）
    private static final String TEST_KEY_1 = "key1";
    private static final String TEST_VALUE_1 = "value1";
    private static final String TEST_KEY_2 = "key2";
    private static final String TEST_VALUE_2 = "value2";


    @Test
    void dataBaseTest() {
        DataBase dataBase = dbConfig.getDataBase();
        String  keyS = "key";


        String  keyV = "value";
        dataBase.insert(TableEnum.PEER, keyS.getBytes(), keyV.getBytes());

        byte[] bytes = dataBase.get(TableEnum.PEER, keyS.getBytes());
        log.info("查询的数据是{} ", Arrays.equals(bytes, keyV.getBytes()));

        byte[] bytes1 = dataBase.get(TableEnum.PEER, keyS.getBytes());
        log.info("查询的数据是{} ", Arrays.equals(bytes1, keyV.getBytes()));

        byte[] bytes12 = dataBase.get(TableEnum.BLOCK, keyS.getBytes());
        log.info("查询的数据是{} ", Arrays.equals(bytes12, keyV.getBytes()));


    }

    /**
     * 测试 iterate 方法：迭代指定表的所有键值对
     */
    @Test
    void iterateTest() {
        DataBase dataBase = dbConfig.getDataBase();
        TableEnum testTable = TableEnum.CHAIN;

        // 1. 先插入测试数据（确保迭代有数据可查）
        dataBase.insert(testTable, TEST_KEY_1.getBytes(), TEST_VALUE_1.getBytes());
        dataBase.insert(testTable, TEST_KEY_2.getBytes(), TEST_VALUE_2.getBytes());
        log.info("已插入测试数据到 {} 表", testTable);

        // 2. 调用 iterate 方法，实现 KeyValueHandler 处理键值对
        log.info("开始迭代 {} 表的所有键值对：", testTable);
        dataBase.iterate(testTable, new KeyValueHandler() {
            @Override
            public boolean handle(byte[] key, byte[] value) {
                // 转换键值为字符串（方便日志查看）
                String keyStr = new String(key);
                String valueStr = new String(value);
                log.info("迭代得到 - 键：{}，值：{}", keyStr, valueStr);

                // 校验测试数据（可选，确保数据正确性）
                if (TEST_KEY_1.equals(keyStr)) {
                    assert TEST_VALUE_1.equals(valueStr) : "键" + TEST_KEY_1 + "的值不匹配";
                }
                if (TEST_KEY_2.equals(keyStr)) {
                    assert TEST_VALUE_2.equals(valueStr) : "键" + TEST_KEY_2 + "的值不匹配";
                }

                // 返回 true 继续迭代，返回 false 终止迭代
                return true;
            }
        });

        // 3. 测试终止迭代功能（迭代到指定键后停止）
        log.info("\n开始迭代，遇到 {} 后终止：", TEST_KEY_1);
        dataBase.iterate(testTable, new KeyValueHandler() {
            @Override
            public boolean handle(byte[] key, byte[] value) {
                String keyStr = new String(key);
                String valueStr = new String(value);
                log.info("迭代得到 - 键：{}，值：{}", keyStr, valueStr);

                // 遇到 TEST_KEY_1 后返回 false，终止迭代
                return !TEST_KEY_1.equals(keyStr);
            }
        });
    }

    /**
     * 测试后清理数据：避免测试数据残留影响后续测试
     */
    @AfterEach
    void cleanData() {
        DataBase dataBase = dbConfig.getDataBase();
        // 清理 CHAIN 表测试数据
        dataBase.delete(TableEnum.CHAIN, TEST_KEY_1.getBytes());
        dataBase.delete(TableEnum.CHAIN, TEST_KEY_2.getBytes());
        dataBase.delete(TableEnum.CHAIN, "key".getBytes());
        // 清理 BLOCK 表测试数据（若有）
        dataBase.delete(TableEnum.BLOCK, "key".getBytes());
        log.info("\n测试数据已清理完成\n");
    }
}
