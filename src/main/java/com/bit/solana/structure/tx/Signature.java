package com.bit.solana.structure.tx;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.checkerframework.checker.units.qual.N;

/**
 * 交易签名（基于Ed25519算法）
 * 每个签名对应一个"需要签名的账户"，用于验证交易的合法性
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class Signature {

    /**
     * 签名字节数组（64字节）
     * 本质是对交易序列化后哈希的加密签名，可通过账户公钥验证
     */
    private byte[] value;  // 固定64字节
}