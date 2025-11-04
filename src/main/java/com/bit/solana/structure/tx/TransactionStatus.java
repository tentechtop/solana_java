package com.bit.solana.structure.tx;

public enum TransactionStatus {
    PENDING,       // 待处理
    PROCESSING,    // 处理中
    CONFIRMED,     // 已确认
    INVALID        // 无效（如签名错误）
}
