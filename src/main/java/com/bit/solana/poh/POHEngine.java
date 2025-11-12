package com.bit.solana.poh;

import com.bit.solana.result.Result;
import com.bit.solana.structure.tx.Transaction;

import java.util.List;

/**
 * POH引擎接口，定义历史证明的核心操作
 * 是 POH（Proof of History）功能的底层核心实现，专注于 POH 哈希链的核心机制，直接处理哈希计算、序列维护、时间戳生成等底层操作。核心职责包括：
 * 维护 POH 哈希链的连续性（通过追加事件、生成空事件）；
 * 计算事件 / 交易的哈希值，生成 POH 记录（包含哈希链位置、物理时间戳等）；
 * 验证 POH 记录链的合法性（哈希连续性、序列完整性）；
 * 管理 POH 的运行状态（启动、停止、状态持久化）。
 */
public interface POHEngine {
    /**
     * 追加事件到POH哈希链
     * @param eventData 事件数据（交易数据/系统事件，null表示空事件）
     * @return 包含POH记录的结果对象
     */
    Result<POHRecord> appendEvent(byte[] eventData);

    /**
     * 为交易生成POH时间戳
     * @param transaction 待处理交易
     * @return 包含POH记录的结果对象
     */
    Result<POHRecord> timestampTransaction(Transaction transaction);

    /**
     * 批量为交易生成POH时间戳
     * @param transactions 交易列表
     * @return 包含POH记录列表的结果对象
     */
    Result<List<POHRecord>> batchTimestampTransactions(List<Transaction> transactions);

    /**
     * 获取当前最新的POH哈希
     * @return 32字节哈希数组
     */
    byte[] getLastHash();


    long getCurrentHeight();

    /**
     * 验证事件序列的合法性
     * @param records 待验证的POH记录列表
     * @return 验证结果（成功/失败原因）
     */
    Result<Boolean> verifyRecords(List<POHRecord> records);

    Result<Boolean> verifyRecord(POHRecord records);


    /**
     * 启动POH引擎
     */
    void start();

    /**
     * 停止POH引擎
     */
    void stop();
}
