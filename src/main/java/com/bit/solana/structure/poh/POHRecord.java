package com.bit.solana.structure.poh;

import com.bit.solana.common.PoHHash;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

/**
 * POH事件记录：符合Solana POH设计的哈希链节点，用于时序锚定与验证
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class POHRecord implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 前一个POH事件的哈希（32字节）
     * 哈希链连续性的核心：currentHash = SHA-256(previousHash + eventHash + emptyEventCounter)
     */
    private byte[] previousHash;

    /**
     * 当前事件的哈希（32字节）
     * - 非空事件：基于事件原始数据计算（如交易哈希）
     * - 空事件：固定为全0字节（无实际数据）
     */
    private byte[] eventHash;

    /**
     * 当前事件的哈希链值（32字节）
     * 由前序哈希、事件哈希、空事件计数器共同计算得出
     */
    private byte[] currentHash;

    /**
     * 空事件计数器
     * - 非空事件：0（表示无连续空事件）
     * - 空事件：累计当前连续空事件数量（如5表示第5个连续空事件）
     */
    private long emptyEventCounter;

    /**
     * 事件类型标识
     * - true：非空事件（交易、区块元数据等有业务意义的事件）
     * - false：空事件（仅维持哈希链时序，无业务数据）
     */
    private boolean isNonEmptyEvent;

    /**
     * 哈希链高度（全局唯一序号）
     * 每追加一个事件（空/非空）则+1，用于标记事件在链中的绝对位置
     */
    private long chainHeight;

    /**
     * 物理时间戳（节点本地时间）
     * 辅助字段，用于将逻辑时间（chainHeight）与实际时间关联
     */
    private Instant physicalTimestamp;

    /**
     * POH链中的位置（逻辑时间戳）
     */
    private long sequenceNumber;

    /**
     * 通用事件ID（32字节，与事件类型匹配）
     * - 交易事件：存储交易ID（txId）
     * - 区块事件：存储区块Hash（blockHash）
     * - 系统事件（如Slot切换）：存储事件专属ID（如Slot+时间戳的哈希）
     * - 空事件：null 或全0字节（无实际业务事件）
     */
    private byte[] eventId;

    /**
     * 事件类型（通过枚举定义，底层存储为1字节）
     * 决定eventId的语义（交易ID/区块Hash等）
     */
    private PohEventType eventType;

    /**
     * 生成该事件的节点ID
     * 分布式场景下用于追踪事件来源
     */
    private byte[] nodeId;


    /**
     * 所属的区块槽位（Slot）
     * 关联POH链与区块链的区块结构
     */
    private long slot;

    /**
     * 节点对当前记录的签名（32字节）
     * 用于验证记录的真实性，防止伪造
     */
    private byte[] nodeSignature;


    /**
     * 检查当前记录是否是空事件
     */
    public boolean isEmptyEvent() {
        return !isNonEmptyEvent;
    }

    public PoHHash getHash() {
        return null;
    }

    public long getHeight() {
        return chainHeight;
    }

    /**
     * 检查当前记录是否是交易事件（替代原有isTransactionRecord()）
     */
    public boolean isTransactionEvent() {
        return PohEventType.TRANSACTION.equals(eventType)
                && eventId != null
                && eventId.length > 0;
    }

    /**
     * 检查当前记录是否是非空事件（兼容原有isNonEmptyEvent的语义）
     */
    public boolean isNonEmptyEvent() {
        return !isEmptyEvent();
    }

    /**
     * 获取事件类型的可读描述（日志/调试用）
     */
    public String getEventTypeDesc() {
        return eventType != null ? eventType.getDescription() : "未设置事件类型";
    }


}