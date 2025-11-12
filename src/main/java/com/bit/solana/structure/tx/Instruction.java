package com.bit.solana.structure.tx;

import lombok.Data;
import java.util.List;

/**
 * 交易指令，定义具体要执行的操作（如转账、调用合约方法）
 * 由指定的程序（智能合约）处理
 */
@Data
public class Instruction {

    /**
     * 程序公钥（32字节）程序ID（32字节，即智能合约的地址）
     * 标识处理该指令的智能合约（如系统程序、代币程序）
     * Solana中所有操作均由程序执行，原生功能（如转账）基于系统程序实现
     */
    private byte[] programId;  // 固定32字节

    /**
     * 程序ID索引（变长整数）
     * 指向 Transaction.accounts 列表中程序账户（智能合约）的位置
     * 例如：若 accounts[2] 是系统程序公钥，则 programIdIndex=2 表示该指令由系统程序处理
     */
    private int programIdIndex;  // 索引值，对应 accounts 列表中的位置


    /**
     * 账户索引列表（u8类型，0-255）
     * 每个索引指向Transaction.accounts中的某个账户，标识该指令仅使用这些账户
     * 作用：缩小指令涉及的账户范围，优化并行执行效率
     */
    private List<Integer> accounts;

    /**
     * 指令数据（字节数组）
     * 包含指令的具体参数（如转账金额、合约方法名及参数）
     * 格式由programId对应的程序定义（需程序自行解析）
     */
    private byte[] data;


}