package com.bit.solana.structure.block;

import com.bit.solana.structure.tx.Transaction;
import lombok.Data;

import java.util.List;

/**
 * Solana区块体，存储区块包含的交易及辅助验证信息
 * 作用：承载实际交易数据，支持快速验证交易完整性和并行执行
 */
@Data
public class BlockBody {

    /**
     * 交易列表（区块包含的所有交易）
     * 交易按PoH时序排序（与区块头的poHHash对应），确保全网对交易顺序的共识
     * 支持0或多个交易（Solana中一个slot可能产生空区块）
     */
    private List<Transaction> transactions;

    /**
     * 交易哈希列表（每个交易的唯一标识，32字节）
     * 与transactions列表一一对应，每个元素是对应交易的SHA-256哈希
     * 作用：
     * 1. 快速验证交易是否被篡改（对比交易序列化后哈希与列表中值）
     * 2. 支持轻节点快速同步（无需下载完整交易，仅通过哈希验证存在性）
     */
    private List<byte[]> transactionHashes;

    /**
     * 交易数量（8字节，uint64）
     * 等价于transactions.size()，但显式存储以优化读取效率（避免遍历列表计数）
     * 用于快速判断区块负载（如TPS计算：transactionsCount / slot间隔时间）
     *
     * 区块体的transactionsCount：服务于交易执行与存储管理区块体是区块的 “实际数据载体”，核心作用是存储和处理交易。例如：
     * 交易并行执行：全节点加载区块体后，无需遍历transactions列表计数（尤其当交易数量达数千笔时，遍历会产生性能开销），可直接读取transactionsCount分配线程池资源；
     * 存储索引：区块体持久化到数据库（如 RocksDB）时，transactionsCount可作为索引字段，快速筛选 “空区块”（transactionsCount=0）或 “高负载区块”，避免全量扫描交易数据。
     */
    private long transactionsCount;

    /**
     * 区块体总字节大小（8字节，uint64）
     * 记录区块体（含所有交易和哈希）的总字节数，用于限制区块大小（Solana协议有最大区块大小限制，防止网络拥塞）
     */
    private long totalSize;

    /**
     * 交易执行的依赖摘要（可选，用于优化并行验证）
     * 存储所有交易涉及的"可写账户公钥哈希"的布隆过滤器（Bloom Filter）
     * 作用：快速判断两笔交易是否存在账户冲突（无需遍历所有账户），加速并行执行调度
     */
    private byte[] accountConflictBloom;
}