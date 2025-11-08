package com.bit.solana.poh;

/**
 * POH 层自定义异常：统一封装异常类型与错误信息，便于问题定位
 */
public class POHException extends RuntimeException {
    // 异常类型枚举：覆盖 POH 层所有可能错误场景

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