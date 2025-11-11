package com.bit.solana.poh;

import com.bit.solana.structure.poh.PohEntry;
import com.bit.solana.structure.tx.Transaction;

import java.util.List;

/**
 * POH(Proof of History)核心接口
 * 提供时间戳生成和验证功能
 * 是基于POHEngine封装的上层业务服务，专注于将 POH 的底层能力转化为业务可用的接口，解决 “POH 如何服务于交易处理等业务场景” 的问题。核心职责包括：
 * 为交易（Transaction）生成 POH 时间戳，并关联到交易对象；
 * 批量处理交易的 POH 时间戳生成；
 * 验证交易相关的 POH 链（如区块中的 POH 条目）；
 * 对外提供 POH 的最新状态（如最新哈希）。
 */
public interface POHService {


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

    POHRecord getCurrentPOH();
}
