package com.bit.solana.txpool;

import com.bit.solana.common.TransactionHash;
import com.bit.solana.result.Result;
import com.bit.solana.structure.tx.Transaction;


import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface TxPool {
    /**
     * 超时丢弃交易池 和 缓冲池
     */
    //使用优先队列（大顶堆） 只需O(n log k)（k=5000），效率更高。
    //从 N 笔交易（N≤100 万）中选出手续费最高的前 5000 笔（若 N<5000 则全选）。

    //明确筛选目标：从 N 笔交易（N≤100 万）中选出手续费最高的前 5000 笔（若 N<5000 则全选）。
    //数据结构选择：使用小顶堆（Min-Heap），仅维护容量为 5000 的堆。堆顶是当前已选 5000 笔中手续费最低的，当新交易手续费高于堆顶时，替换堆顶并调整堆，最终堆中即为结果。
    //优势：时间复杂度 O (N log 5000)（远优于排序的 O (N log N)），空间复杂度 O (5000)（内存占用极低）。


    /**
     * 轻量级校验后放入交易提交池  100万笔交易 每笔500字节  最大1024M最大容量
     * 接收、轻量校验、削峰填谷
     * 轻量级（格式校验、队列入队）
     * 临时存储待验证交易
     * 高吞吐（抗突发流量）
     */

    /**
     * 交易池 10万笔交易 或者 500M最大容量  同时维护当前每字节手续费  交易池保持满载 有空位就去拉取
     * 存储、排序、冲突检测、等待打包
     * 重量级（余额校验、冲突检测、TPS 排序）
     * 低延迟（快速筛选可打包交易）
     */



    /**
     * 布隆过滤器 快速判断 是否不存在
     */

    /**
     * . 窃取机制的核心原理
     * （1）线程本地队列（非完全共享）
     * 每个工作线程（Worker Thread）拥有一个本地任务队列（通常是双端队列，Deque），用于存储分配给它的任务。
     * 线程优先处理自己本地队列中的任务（从队尾取任务，LIFO 顺序），此时无需与其他线程竞争，几乎无锁开销。
     *
     * （2）任务窃取逻辑
     * 当线程 A 的本地队列空了，它会随机选择另一个线程 B，尝试从 B 的队列头部（FIFO 顺序）“窃取” 一个任务执行。
     * 这种 “本地队尾执行，窃取队头” 的设计，可减少线程 A 和线程 B 的操作冲突（A 从 B 的头部取，B 从自己的尾部取，除非队列只剩一个任务，否则不会竞争）。
     */

    /**
     * 堆内存分配 40GB+（避免频繁 GC），使用 ZGC 或 Shenandoah 低延迟 GC，设置-XX:MaxGCPauseMillis=10；
     */
    /**
     * 挑战：ForkJoinPool的任务窃取机制虽高效，但频繁的任务拆分和窃取可能带来调度开销，尤其在任务粒度太小（如单笔交易作为一个任务）时。
     * 优化：
     * 批量处理任务（如每批 100 笔交易），减少任务调度次数；
     * 调整ForkJoinPool的并行度（parallelism参数）与 CPU 核心数匹配（避免超线程过度使用导致的性能下降）；
     * 对耗时差异大的交易（如简单转账 vs 复杂合约调用）分类处理，避免长任务阻塞短任务。
     */

    /**
     * 高性能共识机制（PoH + Tower BFT） 和基于 “优先级费用” 的交易排序逻辑
     */
    /**
     * 超高 TPS 与实时性：Solana 设计目标是高吞吐量（理论上可达 5 万 TPS），交易处理速度极快，未确认交易在网络中停留时间极短（通常毫秒级），无需长期存放在 “池” 中
     */
    /**
     * 基于 PoH 的时间戳排序：Solana 通过 PoH（历史证明）为每个交易分配全局唯一的时间戳，交易按时间戳和 “优先级费用”（priority fee）排序，节点无需通过交易池维护复杂的排序状态
     */
    /**
     * Gulf Stream（湾流协议）：它摒弃传统公链的全网广播与内存池等待模式，会提前 32 个时隙将交易定向推送给即将成为领导者的节点。这就像快递员提前规划路线，让节点能提前预处理交易，减少交易等待打包的时间，大幅降低交易从发出到打包的整体延迟，助力大量交易快速流转提交。
     * Turbine（涡轮协议）：该协议借鉴 BitTorrent 的分片思路，把 128KB 的区块拆成 64 个 2KB 的数据包。节点只需传递部分数据片，再通过树状结构分发，既减轻单点传输压力，又让带宽利用率提升 400%。验证节点可并行接收和处理数据碎片，避免因区块数据量大导致的传播拥堵，保障大量交易数据快速同步。
     * QUIC 传输协议：采用谷歌的 QUIC 协议实现高效网络连接，其 0 - RTT 握手速度是 TCP 的 3 倍，单连接还能支持 100 + 并发流，且具备前向纠错能力，即便 10% 丢包也能完整恢复数据。这让节点间数据传输更快速稳定，为每秒海量交易的传输提供可靠网络支撑。
     */
    /**
     * 出块前存在交易预推送与预验证：Solana 的湾流协议会把经过初步验证的交易，提前推送给未来几个 Slot 的出块领导者节点。这一步不是等待出块时间到才收集交易，而是让领导者提前缓存大量有效交易，为出块时的快速打包做准备，避免出块窗口内临时收集交易导致的时间浪费。
     */
    /**
     * 出块时刻需二次验证与排序：当轮到该领导者的 Slot 出块时间时，它不会直接打包缓存的所有交易。而是会基于 PoH（历史证明）机制，对已收到的交易做二次验证和全局排序 —— 比如核对交易签名有效性、账户余额充足性，以及通过哈希链确认交易的全局唯一顺序，排除无效或存在冲突的交易后，才筛选出合格交易准备打包。
     */
    /**
     * 打包后需共识确认才会上链：领导者将合格交易打包成区块后，并不会立刻上链。这些区块会被广播给全网其他验证者节点，通过 Tower BFT 共识机制完成投票确认。只有获得超过 2/3 验证者的投票支持后，区块才会被正式追加到分布式账本中，交易状态才真正生效。这个共识过程和出块环节衔接紧密，依托 PoH 时间戳大幅降低通信开销，能在极短时间内完成
     */
    /**
     * 存在特殊情况无法打包全部验证交易：一方面，交易有优先级区分，出块时会优先处理手续费更高的交易，若缓存的验证交易数量超出单个区块的承载上限，剩余交易会顺延到下一个 Slot 打包；另一方面，若遇到网络延迟、领导者节点故障等问题，该 Slot 可能无法正常出块，已验证的交易就会由下一个领导者节点接手处理。
     */

    /**
     * 提交交易到交易池（异步）
     * @param transaction 待提交交易
     * @return 处理结果Future
     */
    CompletableFuture<Boolean> submitTransaction(Transaction transaction);

    /**
     * 批量提交交易（异步）
     * @param transactions 交易列表
     * @return 处理结果Future列表
     */
    List<CompletableFuture<Boolean>> batchSubmitTransactions(List<Transaction> transactions);

    /**
     * 获取待处理交易（按优先级）
     * @param maxCount 最大获取数量
     * @return 交易列表
     */
    List<Transaction> getPendingTransactions(int maxCount);

    /**
     * 移除已处理的交易
     * @param transactionIds 已处理交易ID列表
     */
    void removeProcessedTransactions(List<String> transactionIds);

    void removeTransactions(List<TransactionHash> transactionIds);

    /**
     * 获取当前交易池大小
     * @return 交易数量
     */
    int getPoolSize();

    /**
     * 验证交易有效性
     * @param transaction 待验证交易
     * @return 验证结果
     */
    boolean validateTransaction(Transaction transaction);


    Result getTxPool();

    // 获取交易状态
    short getStatus(byte[] txId);

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
