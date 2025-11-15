package com.bit.solana.structure.block;

import com.bit.solana.common.*;
import com.bit.solana.proto.block.Structure;
import com.google.common.hash.BloomFilter;
import com.google.protobuf.ByteString;
import lombok.Data;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

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
    private BlockHash previousBlockHash;

    /**
     * 而 Solana 采用 Tower BFT 共识机制，结合 PoH（历史证明）实现高效的分叉处理：
     * Slot 时序严格性：每个区块对应一个唯一的 Slot（BlockHeader.slot 字段），Slot 由 PoH 全局时钟驱动，全网统一时序（约 400-500ms/Slot）。
     * 领导者唯一性：每个 Slot 预先指定唯一的区块生产者（领导者），只有该领导者在 Slot 内生成的区块才可能成为主链区块，其他节点在同一 Slot 生成的区块会被标记为分叉候选块（Block.isCandidateBlock=true），而非 “叔叔区块”。
     * 快速共识确认：通过 Tower BFT，区块在生成后极短时间内（通常 1-2 个 Slot）即可获得超 2/3 验证节点的确认，分叉链会被快速抛弃，无需保留 “叔叔区块” 作为补偿或验证依据。
     */

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


    /**
     * 在区块链中，为单个区块的 5000 笔交易构建布隆过滤器并嵌入区块头是常见设计（如以太坊的区块布隆过滤器）。其大小可通过布隆过滤器的数学公式精确计算，结果约为 15~20KB
     * 要用于快速判断 “某笔交易是否在区块中”，查询对象是交易哈希（32 字节固定长度）
     */
    BloomFilter<byte[]> txBloomFilter;//交易过滤

    /**
     * 主要用于快速定位 “某合约的某事件是否在区块中”，查询对象是合约地址、事件主题（Topic）
     * 等（如 ERC20 转账事件的Transfer主题）。
     */
    BloomFilter<byte[]> logBloomFilter;//交易过滤

    // 其他方法（保持不变）
    public long getHeaderSize() {
        // 实际实现应计算头部总字节数（可基于Protobuf序列化后的长度）
        try {
            return serialize().length;
        } catch (IOException e) {
            return 0;
        }
    }



   // ========================== 序列化反序列化 ==========================

    /**
     * 序列化当前对象为字节数组（通过Protobuf）
     */
    public byte[] serialize() throws IOException {
        return toProto().toByteArray();
    }

    /**
     * 从字节数组反序列化为BlockHeader（通过Protobuf）
     */
    public static BlockHeader deserialize(byte[] data) throws IOException {
        Structure.ProtoBlockHeader proto = Structure.ProtoBlockHeader.parseFrom(data);
        return fromProto(proto);
    }


    // ========================== Protobuf 转换 ==========================

    /**
     * 转换为Protobuf对象
     */
    public Structure.ProtoBlockHeader toProto() throws IOException {
        Structure.ProtoBlockHeader.Builder builder = Structure.ProtoBlockHeader.newBuilder();

        // 处理字节类型字段（假设自定义哈希类有toBytes()方法）
        if (previousBlockHash != null) {
            builder.setPreviousBlockHash(ByteString.copyFrom(previousBlockHash.toBytes()));
        }
        if (stateRoot != null) {
            builder.setStateRootHash(ByteString.copyFrom(stateRoot.toBytes()));
        }
        if (poHHash != null) {
            builder.setPohHash(ByteString.copyFrom(poHHash.toBytes()));
        }
        if (recentVotesHash != null) {
            builder.setRecentVotesHash(ByteString.copyFrom(recentVotesHash.toBytes()));
        }
        if (feeCalculatorHash != null) {
            builder.setFeeCalculatorHash(ByteString.copyFrom(feeCalculatorHash.toBytes()));
        }

        // 处理基本类型字段
        builder.setBlockTime(blockTime)
                .setPohHeight(poHHeight)
                .setVersion(version)
                .setSlot(slot)
                .setParentSlot(parentSlot)
                .setLeaderScheduleEpoch(leaderScheduleEpoch)
                .setTransactionsCount(transactionsCount);

        // 处理布隆过滤器（序列化为字节数组）
        if (txBloomFilter != null) {
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                txBloomFilter.writeTo(out);
                builder.setTxBloomFilter(ByteString.copyFrom(out.toByteArray()));
            }
        }
        if (logBloomFilter != null) {
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                logBloomFilter.writeTo(out);
                builder.setLogBloomFilter(ByteString.copyFrom(out.toByteArray()));
            }
        }

        return builder.build();
    }

    /**
     * 从Protobuf对象转换为BlockHeader
     */
    public static BlockHeader fromProto(Structure.ProtoBlockHeader proto) throws IOException {
        BlockHeader header = new BlockHeader();

        // 处理字节类型字段（假设自定义哈希类有fromBytes()静态方法）
        if (!proto.getPreviousBlockHash().isEmpty()) {
            header.setPreviousBlockHash(BlockHash.fromBytes(proto.getPreviousBlockHash().toByteArray()));
        }
        if (!proto.getStateRootHash().isEmpty()) {
            header.setStateRoot(StateRootHash.fromBytes(proto.getStateRootHash().toByteArray()));
        }
        if (!proto.getPohHash().isEmpty()) {
            header.setPoHHash(PoHHash.fromBytes(proto.getPohHash().toByteArray()));
        }
        if (!proto.getRecentVotesHash().isEmpty()) {
            header.setRecentVotesHash(RecentVotesHash.fromBytes(proto.getRecentVotesHash().toByteArray()));
        }
        if (!proto.getFeeCalculatorHash().isEmpty()) {
            header.setFeeCalculatorHash(FeeCalculatorHash.fromBytes(proto.getFeeCalculatorHash().toByteArray()));
        }

        // 处理基本类型字段
        header.setBlockTime(proto.getBlockTime());
        header.setPoHHeight(proto.getPohHeight());
        header.setVersion(proto.getVersion());
        header.setSlot(proto.getSlot());
        header.setParentSlot(proto.getParentSlot());
        header.setLeaderScheduleEpoch(proto.getLeaderScheduleEpoch());
        header.setTransactionsCount(proto.getTransactionsCount());

        // 处理布隆过滤器（从字节数组反序列化）


        return header;
    }


}