package com.bit.solana.poh;

import com.bit.solana.structure.poh.PohEntry;
import com.bit.solana.structure.tx.Transaction;

import java.util.List;

/**
 * POH(Proof of History)核心接口
 * 提供时间戳生成和验证功能
 */
public interface POHService {

    /**
     * 追加 POH 事件（空事件/非空事件）
     * @param eventData 事件数据：null = 空事件，非 null = 非空事件（如交易数据、合约事件）
     * @return POHRecord 事件记录（包含当前哈希链状态）
     */
    POHRecord appendEvent(byte[] eventData);

    /**
     * 为交易生成POH时间戳
     * @param transaction 待处理交易
     * @return 带POH信息的交易
     */
    Transaction generateTimestamp(Transaction transaction);

    /**
     * 批量为交易生成POH时间戳
     * @param transactions 交易列表
     * @return 带POH信息的交易列表
     */
    List<Transaction> batchGenerateTimestamp(List<Transaction> transactions);

    /**
     * 验证POH链的有效性
     * @param entry 待验证的POH条目
     * @return 验证结果
     */
    boolean verifyPohChain(PohEntry entry);

    /**
     * 获取当前POH链的最新哈希
     * @return 最新哈希值
     */
    String getLatestHash();
}
