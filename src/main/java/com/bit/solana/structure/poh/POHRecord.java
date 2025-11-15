package com.bit.solana.structure.poh;

import com.bit.solana.common.PoHHash;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

/**
 * POH事件记录：符合Solana POH设计的哈希链节点，用于时序锚定与验证
 * 当交易被节点处理并打包进区块时  节点会将交易作为一个 “事件” 插入 POH 链
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
     * 通用事件ID（32字节，与事件类型匹配）
     * - 交易事件：存储交易ID（txId）
     * - 区块事件：存储区块Hash（blockHash）
     * - 系统事件（如Slot切换）：存储事件专属ID（如Slot+时间戳的哈希）
     * - 空事件：null 或全0字节（无实际业务事件）
     */
    private byte[] eventHash;

    /**
     * POH链中的位置（逻辑时间戳）
     */
    private long sequenceNumber;

    /**
     * 当前事件的哈希链值（32字节）
     * 由前序哈希、事件哈希、空事件计数器共同计算得出
     */
    private byte[] currentHash;

    /**
     * 事件类型（通过枚举定义，底层存储为1字节）
     * 决定eventId的语义（交易ID/区块Hash等）
     */
    private byte eventType;



    /**
     * 检查当前记录是否是空事件（eventHash全零且类型为EMPTY）
     */
    public boolean isEmptyEvent() {
        // 假设空事件的eventType为0x00，且eventHash全零
        if (eventType != PohEventType.EMPTY.getCode()) {
            return false;
        }
        if (eventHash == null) {
            return true;
        }
        for (byte b : eventHash) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }


    /**
     * 检查当前记录是否是交易事件（替代原有isTransactionRecord()）
     */
    public boolean isTransactionEvent() {
        return eventType == PohEventType.TRANSACTION.getCode(); // 假设交易事件的类型值为0x01
    }


    /**
     * 获取事件类型的可读描述
     */
    public String getEventTypeDesc() {
        return PohEventType.fromCode(eventType).getDescription();
    }
}