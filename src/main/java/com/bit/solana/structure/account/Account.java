package com.bit.solana.structure.account;

import lombok.Data;

/**
 * Solana账户实体类，存储区块链状态的基本单元
 * 参考：https://docs.solana.com/developing/programming-model/accounts
 */
@Data
public class Account {

    /**
     * 账户公钥（32字节）
     * 唯一标识账户的地址，由密钥对的公钥生成，全网唯一
     */
    private byte[] pubkey;  // 固定32字节


    /**
     * 账户余额（以lamports为单位，8字节）
     * 类型：uint64
     * 说明：1 SOL = 10^9 lamports，余额用于支付交易费用和租金
     */
    private long lamports;


    /**
     * 所有者公钥（32字节）
     * 控制该账户的程序（智能合约）公钥，账户的修改必须由所有者程序授权
     * 特殊值：
     * - 系统程序公钥：表示账户由系统控制（如普通用户账户）
     * - 程序自身的公钥：表示该账户是程序账户（存储可执行代码）
     */
    private byte[] owner;  // 固定32字节


    /**
     * 账户数据（字节数组）
     * 存储账户的自定义数据，格式由所有者程序定义：
     * - 普通用户账户：通常为空或存储简单元数据
     * - 程序账户（isExecutable=true）：存储智能合约的字节码（可执行程序）
     * - 合约状态账户：存储智能合约的运行时状态（如代币余额、用户数据等）
     * 最大长度：10MB（Solana协议限制）
     */
    private byte[] data;


    /**
     * 是否为可执行账户（程序账户）
     * - true：账户存储可执行代码（智能合约），可被交易指令调用执行
     * - false：普通账户（如用户余额账户、合约状态账户），不可执行
     */
    private boolean isExecutable;


    /**
     * 是否豁免租金
     * Solana通过租金机制回收闲置账户：账户需持有最低余额以豁免租金，否则定期扣除租金直至余额为0（账户关闭）
     * - true：账户余额 >= 最低租金门槛，无需支付租金
     * - false：账户需按周期支付租金
     */
    private boolean hasRentExemption;


    /**
     * 最后修改的epoch（8字节）
     * 类型：uint64
     * 说明：记录账户状态最后一次被修改的epoch（约2天为1个epoch），用于租金计算和状态追踪
     */
    private long lastModifiedEpoch;


    /**
     * 账户是否已关闭
     * 派生属性（无需显式存储）：当lamports=0且data为空时，视为账户已关闭
     */
    public boolean isClosed() {
        return lamports == 0 && (data == null || data.length == 0);
    }
}