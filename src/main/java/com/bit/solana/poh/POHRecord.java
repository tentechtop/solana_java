package com.bit.solana.poh;

import lombok.Data;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * POH 事件记录：存储单个 POH 事件的完整状态，用于哈希链锚定与时序验证
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class POHRecord implements Serializable {
    private static final long serialVersionUID = 1L; // 序列化版本号，避免反序列化异常

    /**
     * 当前事件的哈希值（32字节，SHA-256 结果）
     * - 非空事件：基于 lastHash + 事件数据 + 空事件计数器计算
     * - 空事件：复用 lastHash（仅通过 emptyEventCounter 标识连续空事件）
     */
    private byte[] currentHash;

    /**
     * 空事件计数器
     * - 非空事件：固定为 0（表示无连续空事件）
     * - 空事件：累计当前连续空事件数量（如 5 表示当前是第 5 个连续空事件）
     */
    private long emptyEventCounter;

    /**
     * 事件类型标识
     * - true：非空事件（含业务数据，如交易、合约调用、节点心跳）
     * - false：空事件（仅维持哈希链时序，无业务数据）
     */
    private boolean isNonEmptyEvent;

    /**
     * 全局时序戳（毫秒级）
     * 基于系统时间 + POH 哈希链长度生成，用于快速定位事件在全局时序中的位置
     */
    private long timestamp;

    /**
     * 关联的事件原始数据（仅非空事件有效）
     * 用于后续验证时回溯计算哈希（如交易数据、合约指令）
     */
    private byte[] eventData;

    /**
     * 哈希链高度（当前事件在哈希链中的序号）
     * 从 0 开始递增，每追加一个事件（空/非空）高度 +1，用于快速判断事件先后顺序
     */
    private long chainHeight;
}
