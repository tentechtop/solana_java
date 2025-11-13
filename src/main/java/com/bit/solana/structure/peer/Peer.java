package com.bit.solana.structure.peer;

import com.bit.solana.p2p.enums.NodeServerType;
import lombok.*;

import java.time.Instant;
import java.util.List;

/**
 * 节点信息
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Builder
public class Peer {
    // ==================== 核心标识（唯一确定节点身份） ====================
    /**
     * 节点公钥（32字节，Base58编码字符串）
     * Solana节点的唯一身份标识，用于加密通信、签名验证（如节点消息签名）
     * 例："7Np41oeYqPefeNQEHSv1UDhYrehxin3NStELsSKCT4K2"
     * 节点的公钥 base58编码后的值
     */
    private String id;

    // ==================== 网络信息（用于P2P连接） ====================
    /**
     * 节点IP地址（IPv4/IPv6）
     * 例："192.168.1.100" 或 "2001:db8::1"
     */
    private String host;

    /**
     * P2P通信端口
     */
    private int port;

    /**
     * 节点多地址（Multiaddr格式，P2P网络标准地址格式）
     * 整合IP、端口、协议等信息，方便跨网络层解析
     * 例："/ip4/192.168.1.100/tcp/8000/p2p/7Np41oeYqPefeNQEHSv1UDhYrehxin3NStELsSKCT4K2"
     */
    private String multiaddr;

    /**
     * 节点支持的通信协议版本
     * 用于确保节点间协议兼容性（如"solana-p2p/1.14.19"）
     */
    private String protocolVersion;

    // ==================== 节点状态（反映节点当前状态） ====================
    /**
     * 节点类型（区分功能角色）
     * - VALIDATOR：验证者节点（参与共识）
     * - FULL_NODE：全节点（存储完整账本，不参与共识）
     * - LIGHT_NODE：轻节点（仅同步区块头，不存储完整数据）
     */
    private NodeServerType nodeType;

    /**
     * 是否在线 true在线 / false 离线
     */
    private boolean isOnline;

    /**
     * 节点最新同步的区块槽位（Slot）
     * 反映节点账本同步进度，用于判断节点数据新鲜度
     * 例：198765432
     */
    private long latestSlot;

    /**
     * 最后一次与当前节点成功通信的时间
     * 用于判断节点是否在线（如超过5分钟无通信则标记为离线）
     */
    private Instant lastContactTime;

    // ==================== 能力与属性（描述节点功能） ====================
    /**
     * 节点是否为验证者（参与共识）
     * 等价于 nodeType == NodeType.VALIDATOR，方便快速判断
     */
    private boolean isValidator;

    /**
     * 验证者节点的质押量（SOL，仅验证者有效）
     * 反映节点在共识中的权重（质押量越高，被选为领袖的概率越高）
     */
    private double stakeAmount;

    /**
     * 节点支持的服务能力
     * 例：["transaction-relay", "block-propagation", "rpc"]
     * - transaction-relay：支持交易转发
     * - block-propagation：支持区块广播
     * - rpc：提供RPC服务
     */
    private List<String> capabilities;

    /**
     * 节点软件版本（如"SOLANA_VERSION=1.14.19"）
     * 用于排查版本兼容问题（如某些功能仅高版本支持）
     */
    private String softwareVersion;


}
