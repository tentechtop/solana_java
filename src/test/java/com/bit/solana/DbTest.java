package com.bit.solana;

import com.bit.solana.common.BlockHash;
import com.bit.solana.database.DataBase;
import com.bit.solana.database.DbConfig;
import com.bit.solana.structure.block.Block;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
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
        dataBase.insert("chain",keyS.getBytes(),keyV.getBytes());


        byte[] bytes = dataBase.get("chain", keyS.getBytes());
        log.info("查询的数据是{} ", Arrays.equals(bytes, keyV.getBytes()));

        byte[] bytes1 = dataBase.get("block", keyS.getBytes());
        log.info("查询的数据是{} ", Arrays.equals(bytes1, keyV.getBytes()));
    }
}
