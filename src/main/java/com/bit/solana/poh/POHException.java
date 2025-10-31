package com.bit.solana.poh;

/**
 * POH 层自定义异常：统一封装异常类型与错误信息，便于问题定位
 */
public class POHException extends RuntimeException {
    // 异常类型枚举：覆盖 POH 层所有可能错误场景
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

    // 异常类型（用于分类处理）
    private final ErrorType errorType;

    // 构造方法：无cause异常
    public POHException(ErrorType errorType, String message) {
        super("[" + errorType.getDesc() + "]：" + message);
        this.errorType = errorType;
    }

    // 构造方法：带cause异常（链式追踪）
    public POHException(ErrorType errorType, String message, Throwable cause) {
        super("[" + errorType.getDesc() + "]：" + message, cause);
        this.errorType = errorType;
    }

    // Getter：获取异常类型
    public ErrorType getErrorType() {
        return errorType;
    }
}