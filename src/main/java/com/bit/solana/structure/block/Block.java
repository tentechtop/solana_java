package com.bit.solana.structure.block;

import com.bit.solana.common.BlockHash;
import com.google.common.hash.BloomFilter;
import lombok.Data;

import java.util.List;

/**
 * Solana完整内存区块模型
 * 包含区块的核心数据（头+体）、唯一标识、内存状态、索引信息，适配节点内存中的区块处理流程
 * 参考Solana区块结构规范：https://docs.solana.com/architecture/blockchain#block-structure
 */
@Data
public class Block {
    //区块数据会被分片存储
    //4MB 区块拆分为 16 个 256KB 分片
    //低带宽节点若同时从 4 个邻居节点下载，每个节点承担 4 个分片（1MB 数据），则单节点下载时间为 1MB×8/10Mbps=0.8 秒
    //每一个区块最多包含5000笔交易

    // ========================== 1. 区块唯一标识（核心索引字段）==========================

    /**
     * 区块哈希（32字节，SHA-256）
     * 计算逻辑：对BlockHeader和BlockBody序列化后的字节流，进行SHA-256哈希得到
     * 作用：区块的全局唯一标识，用于区块链的链式关联（父区块哈希指向此值）、防篡改验证
     */
    private BlockHash blockHash;

    /**
     * 父区块哈希（32字节）
     * 直接引用：从BlockHeader的previousBlockhash字段同步而来
     * 作用：内存中快速追溯父区块，避免每次从header中解析，提升链结构遍历效率（如分叉处理）
     */
    private BlockHash parentBlockHash;

    // ========================== 2. 区块核心数据（头+体）==========================
    /**
     * 区块头：存储区块元数据（时序、共识、状态摘要）
     * 内存中保持与BlockBody的强关联，确保头体数据一致性
     */
    private BlockHeader header;

    /**
     * 区块体：存储实际交易数据及辅助验证信息
     * 内存中需确保transaction列表与transactionHashes一一对应
     */
    private BlockBody body;

    // ========================== 3. 区块大小信息（内存/传输管控）==========================
    /**
     * 区块头大小（字节数，long类型）
     * 计算逻辑：BlockHeader序列化后的字节数组长度
     * 作用：内存占用统计、网络传输分片（若区块头过大）、协议大小限制校验（Solana对区块头大小有隐含限制）
     */
    private long headerSize;

    /**
     * 区块体大小（字节数，long类型）
     * 计算逻辑：BlockBody序列化后的字节数组长度
     * 作用：与BlockBody的totalSize字段双重校验（防止序列化错误），内存回收时的大小参考
     */
    private long bodySize;

    /**
     * 区块总大小（字节数，long类型）
     * 计算逻辑：headerSize + bodySize
     * 作用：快速判断是否超过Solana协议的区块最大限制（默认约1.4MB），避免内存中存储非法大小的区块
     */
    private long totalSize;

    // ========================== 4. 区块索引信息（快速查询）==========================
    /**
     * 区块插槽号（long类型，对应BlockHeader.slot）
     * 直接引用：从BlockHeader的slot字段同步而来
     * 作用：内存中按slot快速排序、查询（如“获取slot=12345的区块”），无需解析header，提升索引效率
     */
    private long slot;

    /**
     * 区块所属周期（epoch，long类型）
     * 计算逻辑：根据Solana的epoch划分规则（1 epoch ≈ 2天，约432000 slot），由slot推导得出
     * 作用：内存中按epoch分组管理区块（如epoch切换时的共识参数更新），简化周期内的区块遍历
     */
    private long epoch;

    // ========================== 5. 区块状态信息（内存中处理流程标识）==========================
    /**
     * 区块状态（枚举类型）
     * 取值：UNVERIFIED（未验证）、VERIFIED（已验证）、CONFIRMED（已共识确认）、ARCHIVED（已归档）
     * 作用：标识区块在内存中的处理阶段，如：
     * - 网络模块接收区块后设为UNVERIFIED；
     * - 验证模块通过合法性校验后设为VERIFIED；
     * - 共识模块达成超2/3投票后设为CONFIRMED；
     * - 区块被写入持久化存储后设为ARCHIVED（内存中可标记为待回收）
     * 区块状态（用byte表示，1字节，节省内存）
     * 详见 {@link com.bit.solana.structure.block.BlockStatusResolver}
     */
    private short blockStatus;

    /**
     * 是否为候选区块（分叉场景）
     * 取值：true（分叉候选）、false（主链区块）
     * 作用：内存中区分主链与分叉区块，共识模块决策时优先选择主链区块，避免分叉扩散
     */
    private boolean isCandidateBlock;

    // ========================== 6. 时间戳信息（内存中时序追踪）==========================
    /**
     * 区块接收时间（毫秒级Unix时间戳，long类型）
     * 赋值时机：节点网络模块从P2P网络接收到完整区块（头+体）时记录
     * 作用：统计区块从“网络接收”到“验证完成”的耗时，监控节点处理性能
     */
    private long receivedTimestamp;

    /**
     * 区块验证完成时间（毫秒级Unix时间戳，long类型）
     * 赋值时机：验证模块完成区块合法性校验（哈希、签名、状态根等）后记录
     * 作用：判断区块处理是否超时，超时区块可标记为无效并回收内存
     */
    private long verifiedTimestamp;

    // ========================== 7. 共识相关辅助字段（内存中共识处理）==========================
    /**
     * 验证节点签名列表（每个签名64字节，Ed25519算法）
     * 存储内容：已对当前区块投“确认票”的验证节点签名
     * 作用：共识模块快速统计投票数量（无需从P2P网络重复拉取），判断是否达到超2/3投票（Tower BFT共识条件）
     */
    private List<byte[]> validatorSignatures;

    /**
     * 投票数量（long类型）
     * 计算逻辑：validatorSignatures.size()
     * 作用：快速判断是否满足共识确认条件（如投票数 >= 2/3 * 总验证节点数），避免遍历列表计数
     */
    private long signatureCount;

    // ========================== 8. 内存管理辅助字段==========================
    /**
     * 内存占用标记（是否为“热点区块”）
     * 取值：true（热点，近期频繁访问）、false（非热点，可优先回收）
     * 作用：节点内存不足时，优先回收非热点区块（如已归档的旧区块），保留热点区块（如当前slot附近的区块）
     */
    private boolean isHotBlock;


}
