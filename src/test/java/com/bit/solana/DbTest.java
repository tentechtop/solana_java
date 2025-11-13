package com.bit.solana;

import com.bit.solana.database.DataBase;
import com.bit.solana.database.DbConfig;
import com.bit.solana.database.rocksDb.TableEnum;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Arrays;

@Slf4j
@SpringBootTest
public class DbTest {

    @Autowired
    private DbConfig DbConfig;


    @Test
    void dataBaseTest() {
        DataBase dataBase = DbConfig.getDataBase();
        String  keyS = "key";
        String  keyV = "value";
        dataBase.insert(TableEnum.CHAIN,keyS.getBytes(),keyV.getBytes());

        byte[] bytes = dataBase.get(TableEnum.CHAIN, keyS.getBytes());
        log.info("查询的数据是{} ", Arrays.equals(bytes, keyV.getBytes()));

        byte[] bytes1 = dataBase.get(TableEnum.BLOCK, keyS.getBytes());
        log.info("查询的数据是{} ", Arrays.equals(bytes1, keyV.getBytes()));
    }
}
