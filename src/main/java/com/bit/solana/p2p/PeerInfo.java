package com.bit.solana.p2p;

import lombok.*;

/**
 * 节点信息
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Builder
public class PeerInfo {
    /**
     * 用 X25519 替代 RSA 做密钥交换
     * 如果想使用椭圆曲线算法优化 P2P 加密流程（相比 RSA 更高效、密钥更短），可以用 X25519（椭圆曲线 Diffie-Hellman，ECDH）进行密钥协商，步骤如下：
     * 密钥生成：每次会话时，双方生成临时的 X25519 密钥对（私钥 + 公钥）。
     * 密钥交换：
     * 双方交换公钥；
     * 各自用本地私钥和对方公钥通过 X25519 计算出相同的共享密钥（对称密钥的种子）。
     * 后续通信：用共享密钥派生 AES 对称密钥，后续数据用 AES 加密传输（与之前的流程一致）。
     */


    /**
     * 密钥协商：对于需要加密的通信（如基于 TLS 或 Noise 协议），
     * 双方会通过公钥协商出一个临时的 对称加密密钥（如 ECDHE 密钥交换），
     * 后续通信使用该对称密钥加密（兼顾安全性和性能）。
     */

    private String peerId; //32字节核心公钥 -> 编码后长度是40  节点公钥的base58编码 -> 可以还原成公钥用于验证

    /**
     * P2P 通信加密（非对称 + 对称结合）
     */



}
