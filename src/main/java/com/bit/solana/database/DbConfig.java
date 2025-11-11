package com.bit.solana.database;

import lombok.Data;

@Data
public class DbConfig {
    private String dbName;
    private String path;//保存路径
    private String username;
    private String password;
    private Integer maxSize;//最大内存占用大小  MB
}
