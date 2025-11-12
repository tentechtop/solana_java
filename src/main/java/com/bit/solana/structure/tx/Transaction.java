package com.bit.solana.structure.tx;

import com.bit.solana.common.BlockHash;
import com.bit.solana.common.TransactionHash;
import com.bit.solana.structure.poh.POHRecord;
import com.bit.solana.structure.account.AccountMeta;
import lombok.Data;

import java.util.Arrays;
import java.util.List;

import static com.bit.solana.util.Sha.applySHA256;

/**
 * Solana交易实体类，包含完整的交易信息
 * 参考：https://docs.solana.com/developing/programming-model/transactions
 * 目标：10万TPS提交，1万TPS处理成功，500ms超时，400ms出块
 */
@Data
public class Transaction {
    //单笔交易最大大小为 1232字节 12*12*10  = 12.kb

    //合约大小最大 256KB - 512KB

    /**
     * 交易ID缓存
     */
    private byte[] txId;

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

    private POHRecord pohRecord;

    /**
     * 生成交易ID（txId）：取第一个签名的字节数组，转换为十六进制字符串
     * Solana中交易ID本质是第一个签名的哈希标识
     */
    public byte[] getTxId() {
        if (txId == null) {
            // 校验：有效的交易必须至少有一个签名（费用支付者的签名）
            if (signatures == null || signatures.isEmpty()) {
                throw new IllegalStateException("交易没有签名，无法生成txId");
            }
            Signature signature = signatures.getFirst();
            byte[] value = signature.getValue();
            byte[] bytes = applySHA256(value);
            txId = bytes;
        }
        return txId;
    }

    /**
     * 获取交易发送者（费用支付者）
     * @return
     */
    public byte[] getSender() {
        // 1. 校验账户列表不为空
        if (accounts == null || accounts.isEmpty()) {
            throw new IllegalStateException("交易未包含任何账户，无法确定发送者（费用支付者）");
        }

        // 2. 遍历账户列表，寻找第一个"isSigner=true且isWritable=true"的账户（费用支付者）
        for (AccountMeta account : accounts) {
            if (account.isSigner() && account.isWritable()) {
                // 3. 返回该账户的公钥（即发送者地址）
                return account.getPublicKey();
            }
        }

        // 3. 若未找到符合条件的账户，说明交易无效（Solana要求交易必须有费用支付者）
        throw new IllegalStateException("交易中未找到有效的费用支付者（需满足isSigner=true且isWritable=true）");
    }

    public TransactionHash getTransactionHash() {

        return null;
    }


    public boolean isValidPOHRecord() {
        if (pohRecord == null) {
            return false;
        }
        // 验证POH记录中的交易ID与当前交易ID一致
        return Arrays.equals(pohRecord.getEventId(), this.getTxId());
    }

}