package com.bit.solana.structure.block;

/**
 * 内存中区块的处理状态枚举
 */
public enum BlockStatus {
    /** 未验证：刚从网络接收，未完成合法性校验（哈希、签名、状态根等） */
    UNVERIFIED,
    /** 已验证：通过合法性校验，但未达成共识确认 */
    VERIFIED,
    /** 已共识确认：获得超2/3验证节点投票，成为主链区块 */
    CONFIRMED,
    /** 已归档：已写入持久化存储（如RocksDB），内存中可标记为待回收 */
    ARCHIVED,
    /** 无效：校验失败（如哈希不匹配、签名非法），待内存回收 */
    INVALID,

    ;

    /**
     * 快速判断是否为终态（无需再处理的状态）
     * 终态：CONFIRMED（已确认）、ARCHIVED（已归档）、INVALID（无效）
     */
    public boolean isTerminal() {
        return this == CONFIRMED || this == ARCHIVED || this == INVALID;
    }

    /**
     * 快速判断是否为有效状态（未失效且未归档）
     */
    public boolean isValid() {
        return this == UNVERIFIED || this == VERIFIED || this == CONFIRMED;
    }
}