package com.bit.solana.blockchain;

import com.bit.solana.result.Result;
import com.bit.solana.structure.block.Block;

/**
 * 从代码片段看，BlockChain接口定义了区块链的基础能力（交易验证、区块生成等），BlockChainImpl作为其实现类，应聚焦于：
 * 区块的生成、验证和存储（全局状态维护）；
 * 交易的最终确认（结合共识结果）；
 * 区块链核心逻辑的串联（如 POH 时序与区块的绑定）。
 * 若将质押和投票逻辑嵌入其中，会导致类职责过重，不符合单一职责原则，且难以维护和扩展。
 */
public interface BlockChain {
    Result processBlock(Block block);

    Block getLatestBlock();

    void generateBlock(long currentHeight, byte[] clone);

    //区块链本身的验证功能
    //区块链作为分布式账本系统，提供了全局的、最终的交易验证：
    //区块验证：当交易被打包进区块后，全网节点会验证整个区块的有效性，包括区块结构、共识证明和所有交易的执行结果
    //状态转换验证：验证交易对区块链全局状态的改变是否合法（如账户余额是否足够、智能合约执行是否成功）
    //共识验证：通过共识机制（如 PoW、PoS）确认区块的有效性和顺序，确保交易被不可逆地记录
    //全局唯一性：确保交易在整个区块链历史中是唯一的，彻底防止双花问题

    /**
     * 交易验证
     */


    /**
     * 固定生成区块
     */
}
