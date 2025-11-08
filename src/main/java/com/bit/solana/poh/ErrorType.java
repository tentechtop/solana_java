package com.bit.solana.poh;

public enum ErrorType {
    HASH_COMPUTE_FAILED("哈希计算失败（SHA-256 算法异常）"),
    CACHE_OP_FAILED("缓存操作失败（读取/写入缓存异常）"),
    PERSIST_FAILED("数据持久化失败（本地文件读写异常）"),
    EVENT_VERIFY_FAILED("POH 事件验证失败（哈希不匹配/计数器非法）"),
    EMPTY_COUNTER_OVERFLOW("空事件计数器溢出（超过最大阈值）"),
    CONFIG_INVALID("POH 配置无效（如缓存实例为空/参数非法）");

    private final String desc;

    ErrorType(String desc) {
        this.desc = desc;
    }

    public String getDesc() {
        return desc;
    }
}
