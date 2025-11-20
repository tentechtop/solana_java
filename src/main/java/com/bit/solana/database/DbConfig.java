package com.bit.solana.database;

import com.bit.solana.database.rocksDb.RTable;
import com.bit.solana.p2p.PeerService;
import com.bit.solana.p2p.impl.PeerServiceImpl;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.checkerframework.checker.units.qual.A;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "system")
public class DbConfig {
    private String path;//保存路径
    private String username;
    private String password;
    private Integer maxSize;//最大内存占用大小 MB

    @Autowired
    private DataBase dataBase;


    @PostConstruct
    public void init() {
        log.info("系统数据路径:{}",path);
        boolean database = dataBase.createDatabase(this);
        if (!database) {
            throw new RuntimeException("数据库创建失败");
        }
    }



}
