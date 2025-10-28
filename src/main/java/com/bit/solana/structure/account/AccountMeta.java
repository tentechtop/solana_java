package com.bit.solana.structure.account;

import com.bit.solana.common.PubkeyHash;
import lombok.Data;

/**
 * 账户元数据，描述交易涉及的账户及其权限
 */
@Data
public class AccountMeta {

    /**
     * 账户公钥（32字节）
     * 唯一标识区块链上的账户（如用户账户、程序账户）
     */
    private PubkeyHash pubkey;  // 固定32字节

    /**
     * 是否为签名账户
     * true：该账户需在signatures列表中提供对应签名
     * false：无需签名（如只读账户、程序账户）
     */
    private boolean isSigner;

    /**
     * 是否为可写账户
     * true：交易可修改该账户的状态（如余额、数据）
     * false：交易仅能读取该账户状态（不可修改）
     * 注：两笔交易修改同一可写账户会产生冲突，需串行执行
     */
    private boolean isWritable;
}