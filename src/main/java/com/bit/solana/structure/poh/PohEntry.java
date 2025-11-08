package com.bit.solana.structure.poh;

import lombok.Data;

import java.io.Serializable;

/**
 * POH(Proof of History)条目实体类
 * 存储POH链中的单个节点信息，包含哈希值、时间戳等关键数据
 */
@Data
public class PohEntry implements Serializable {
    private static final long serialVersionUID = 1L;

    // 当前POH条目的哈希值（SHA-256计算结果）
    private String hash;

    // 前一个POH条目的哈希值（形成哈希链）
    private String previousHash;

    // 时间戳（纳秒级，用于标记交易在POH链中的时间位置）
    private long timestamp;

    // 计数器（哈希链增长的序号，用于确保哈希唯一性）
    private long counter;

    // 关联的交易ID（用于绑定到具体交易）
    private String transactionId;

    // 交易关键数据（用于哈希计算的原始数据片段，如交易签名、发送者等）
    private String transactionData;

}
