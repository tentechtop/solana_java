package com.bit.solana;

import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import java.nio.charset.StandardCharsets;

/**
 * 可以快速判断 “元素不存在”（100% 准确）  误判元素存在
 */
public class BloomFilterDemo {
    /**
     * 高频无效查询、海量数据过滤等
     * @param args
     */

    /**
     * 交易是否已上链
     * @param args
     */

    /**
     * 交易是否已上链
     * 问题：节点查询某笔交易是否存在时，
     * 若直接查 RocksDB/LevelDB，大量不存在的交易（如恶意查询、用户误操作）会导致无效磁盘 IO，拖慢性能。
     * 解决方案：
     * 维护一个 “已上链交易哈希布隆过滤器”，新交易写入区块时同步插入过滤器；
     * 查询时先查布隆过滤器：若返回 “不存在”，直接返回结果（100% 准确）；若返回 “可能存在”，再查数据库确认（处理误判）。
     * 效果：过滤 90% 以上的无效查询，磁盘 IO 减少一个数量级。
     * @param args
     */

    /**
     * 区块高度合法性校验
     * 问题：同步区块时，恶意节点可能伪造不存在的区块高度（如当前最高高度 10 万，却发送高度 20 万的区块）。
     * 解决方案：用布隆过滤器缓存 “已确认区块高度”，收到新区块时先校验高度是否在过滤器中，快速拦截明显无效的高度。
     * @param args
     */

    /**
     * 问题：用户可能向不存在的地址转账（如输入错误），区块链节点需验证地址有效性，若直接查数据库，无效地址会导致大量无效查询。
     * 解决方案：
     * 维护 “已创建账户地址布隆过滤器”（如以太坊的账户模型、Solana 的公钥地址）；
     * 转账时先查过滤器：若地址 “一定不存在”，直接拒绝交易并提示用户，避免无效的链上操作。
     * @param args
     */

    /**
     * 节点数据可用性过滤
     * 问题：P2P 网络中，节点同步数据时（如区块、交易），需先判断其他节点是否持有目标数据，盲目请求会浪费带宽。
     * 解决方案：
     * 每个节点用布隆过滤器广播 “自己已存储的数据哈希范围”（如最近 1000 个区块哈希）；
     * 同步时先通过对方的过滤器判断数据是否 “可能存在”，仅向有潜力的节点发送请求。
     * @param args
     */

    /**
     * 问题：轻节点（如手机钱包）无法存储全量数据，查询时需向全节点请求，若返回无关数据会浪费流量。
     * 解决方案：轻节点向全节点发送 “目标数据的布隆过滤器”（如用户相关的交易哈希），全节点仅返回过滤器匹配的数据，减少传输量。
     * @param args
     */

    /**
     * 过滤器更新策略：
     * 新区块 / 交易写入时，同步更新过滤器（避免滞后）；
     * 历史数据过滤器可定期重建（如每天一次），避免长期运行导致误判率上升。
     * @param args
     */


    public static void main(String[] args) {
        // 1. 创建布隆过滤器：预计插入100万个元素，误判率0.01（1%）
        BloomFilter<String> bloomFilter = BloomFilter.create(
                Funnels.stringFunnel(StandardCharsets.UTF_8), // 数据类型转换器
                1_000_000, // 预计插入元素数量
                0.01       // 可接受的误判率（越小，需要的bit数组越大）
        );

        // 2. 插入元素
        bloomFilter.put("区块链");
        bloomFilter.put("比特币");
        bloomFilter.put("以太坊");

        // 3. 查询元素
        System.out.println(bloomFilter.mightContain("区块链"));  // true（存在）
        System.out.println(bloomFilter.mightContain("Solana")); // false（一定不存在）
        System.out.println(bloomFilter.mightContain("未知元素")); // 可能为true（误判）
    }
}