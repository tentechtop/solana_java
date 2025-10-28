package com.bit.solana.structure.tx;

import com.bit.solana.common.BlockHash;
import com.bit.solana.structure.account.AccountMeta;
import lombok.Data;
import java.util.List;

/**
 * Solana交易实体类，包含完整的交易信息
 * 参考：https://docs.solana.com/developing/programming-model/transactions
 */
@Data
public class Transaction {

    /**
     * 签名列表（每个签名64字节）
     * 与accounts中"isSigner=true"的账户一一对应，证明账户所有者授权交易
     */
    private List<Signature> signatures;

    /**
     * 账户元数据列表（交易涉及的所有账户）
     * 包含账户公钥、是否为签名账户、是否可写等信息
     */
    private List<AccountMeta> accounts;

    /**
     * 指令列表（交易要执行的具体操作）
     * 每个指令由指定的程序（智能合约）处理
     */
    private List<Instruction> instructions;

    /**
     * 最近区块哈希（32字节）
     * 用于防重放攻击和控制交易有效期（通常需在300个slot内，约2分钟）
     */
    private BlockHash recentBlockhash;

    /**
     * 隐含字段：费用支付者（无需显式定义）
     * 规则：accounts中第一个"isSigner=true且isWritable=true"的账户即为费用支付者
     */
}