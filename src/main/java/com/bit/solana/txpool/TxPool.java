package com.bit.solana.txpool;

import com.bit.solana.result.Result;
import com.bit.solana.structure.tx.Transaction;

public interface TxPool {

    Result getTxPool();

    Result<String> getTxPoolStatus();

    //交易池的验证功能
    //交易池 (也称内存池 / Mempool) 是区块链节点的重要组件，主要负责：
    //初步验证：检查交易的基本格式、签名有效性和发送方余额是否充足
    //防双花检查：验证交易是否已被处理或存在 "双花" 风险
    //格式验证：确保交易符合网络协议规则和静态格式要求
    //暂存管理：将通过初步验证的交易暂存，等待打包进区块
    //交易池验证的特点是局部性和临时性，它只验证交易的基本要素，不涉及区块链全局状态的最终确认。
    /**
     * 交易验证
     * @param tx
     * @return true/验证成功 无消息 false/验证失败 有失败消息
     */
    Result<String> verifyTransaction(Transaction tx);


    /**
     * 添加一笔交易到交易池中
     * @param tx
     * @return true/添加成功 无消息 false/添加失败 有失败消息
     */
    Result<String> addTransaction(Transaction tx);  // 添加交易

    void processTransactions();       // 并行处理交易
}
