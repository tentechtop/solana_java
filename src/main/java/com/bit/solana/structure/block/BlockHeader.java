package com.bit.solana.structure.block;

import com.bit.solana.common.*;
import lombok.Data;

/**
 * Solana区块头结构，包含区块核心元数据，用于验证区块合法性、维护链连续性及支持共识机制
 * 参考Solana协议规范：https://docs.solana.com/developing/programming-model/transactions#block-structure
 */
@Data
public class BlockHeader {

    /**
     * 前序区块哈希（32字节）
     * 作用：上一区块的唯一标识，通过哈希链绑定当前区块与历史区块，确保区块链不可篡改性
     */
    private BlockHash previousBlockhash;

    /**
     * 状态根哈希（32字节）
     * 作用：区块执行后所有账户状态的Merkle根哈希，用于快速验证全局状态一致性
     */
    private StateRootHash stateRoot;

    /**
     * 区块提议时间戳（毫秒级Unix时间戳，8字节）
     * 类型：uint64
     * 作用：领导者节点提议区块的物理时间（辅助时序，核心时序依赖PoH）
     */
    private long blockTime;

    /**
     * PoH哈希（32字节）
     * 作用：当前区块在PoH全局哈希链中的哈希值，标识区块的时序位置
     */
    private PoHHash poHHash;

    /**
     * PoH高度（8字节）
     * 类型：uint64
     * 作用：PoH哈希链的累计长度（哈希计算次数），量化区块在时序链中的位置
     */
    private long poHHeight;

    /**
     * 协议版本号（8字节）
     * 类型：uint64
     * 作用：标识区块结构的协议版本，用于节点兼容性判断
     */
    private long version;

    /**
     * 插槽号（8字节）
     * 类型：uint64
     * 作用：区块的逻辑位置标识，由PoH时钟驱动（约400ms/slot），全网统一时序基准
     */
    private long slot;

    /**
     * 父插槽号（8字节）
     * 类型：uint64
     * 作用：当前区块的父区块所在slot，用于处理区块链分叉
     */
    private long parentSlot;

    /**
     * 最近投票哈希（32字节）
     * 作用：验证节点最近投票集合的哈希摘要，用于快速验证区块是否满足Tower BFT共识条件（超2/3投票）
     */
    private RecentVotesHash recentVotesHash;

    /**
     * 领导者调度周期（8字节）
     * 类型：uint64
     * 作用：当前区块领导者所属的epoch（约2天），用于验证领导者身份合法性
     */
    private long leaderScheduleEpoch;

    /**
     * 交易数量（8字节）
     * 类型：uint64
     * 作用：当前区块包含的交易总数，用于统计区块负载
     * 区块体中已经存在为什么还要重复
     * 不同模块的职责定位和实际运行场景的效率 / 安全性需求设计的，
     * 核心是 “让数据在需要的地方直接可用”，避免跨模块依赖或重复计算的开销
     * 区块头的transactionsCount：服务于共识与链连续性验证
     * 区块头是区块的 “元数据摘要”，核心作用是让验证节点快速判断区块的合法性（无需加载完整交易数据）。例如：
     * 共识阶段：验证节点通过区块头的transactionsCount快速判断区块是否符合 “单 slot 交易数量限制”（防止恶意节点打包过多交易导致网络拥塞）；
     * 链同步：轻节点（如手机钱包）仅下载区块头时，可通过transactionsCount快速了解区块负载（如 TPS 计算），无需下载完整区块体。
     */
    private long transactionsCount;

    /**
     * 费用计算器哈希（32字节）
     * 作用：交易费用计算规则的哈希，确保全网按统一规则计算交易费用
     */
    private FeeCalculatorHash feeCalculatorHash;

    public long getHeaderSize() {
        return 0;
    }
}