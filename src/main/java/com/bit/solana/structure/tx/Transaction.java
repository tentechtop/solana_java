package com.bit.solana.structure.tx;

import com.bit.solana.common.BlockHash;
import com.bit.solana.common.TransactionHash;
import com.bit.solana.result.Result;
import com.bit.solana.structure.poh.POHRecord;
import com.bit.solana.structure.account.AccountMeta;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import static com.bit.solana.util.ByteUtils.bytesToHex;
import static com.bit.solana.util.Sha.applySHA256;

/**
 * Solana交易实体类，包含完整的交易信息
 * 参考：https://docs.solana.com/developing/programming-model/transactions
 * 目标：10万TPS提交，1万TPS处理成功，500ms超时，400ms出块
 * 签名的本质是证明交易发起者（私钥持有者）认可该交易的全部内容，防止交易被篡改或伪造。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
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

    /**
     * POH时序
     */
    private POHRecord pohRecord;

    /**
     * 每字节手续费
     */
    private long fee;

    /**
     * 字节
     */
    private long size; //字节

    /**
     * 提交时间
     */
    private long submitTime;


    private short status;


    private CompletableFuture<Result<String>> future;


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

    public String getTxIdStr() {
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
        return bytesToHex(txId);
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



    //签名的本质是证明交易发起者（私钥持有者）认可该交易的全部内容，防止交易被篡改或伪造。
    //需要签名的数据有
    //1、对参与交易的accounts 中的公钥进行签名 将公钥  N* 32字节 拼接在一起
    //2、最新区块Hash recentBlockhash 32字节  同上拼接在一起
    //3、将指令数据 //拼接在一起
    //最后执行Sha256Hash
    // 并对这个Hash签名 生成签名数据

    /**
     * 构建待签名的原始数据（按Solana规范序列化交易核心字段）
     * 签名数据 = 序列化(账户元数据列表 + 最近区块哈希 + 指令列表)
     * @return 待签名的字节数组
     */
    public byte[] buildSignData() {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {

            // 1. 序列化账户元数据列表（Account Metas）
            serializeAccounts(dos);

            // 2. 序列化最近区块哈希（Recent Blockhash）
            serializeRecentBlockhash(dos);

            // 3. 序列化指令列表（Instructions）
            serializeInstructions(dos);

            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("构建交易签名数据失败", e);
        }
    }

    /**
     * 序列化账户元数据列表
     * 格式：[账户数量(变长整数)] + [每个账户的序列化数据]
     * 每个账户数据：[公钥(32字节)] + [isSigner(1字节)] + [isWritable(1字节)]
     */
    private void serializeAccounts(DataOutputStream dos) throws IOException {
        Objects.requireNonNull(accounts, "账户列表不能为空");
        // 写入账户数量（使用Solana变长整数编码，节省空间）
        writeVarInt(dos, accounts.size());
        // 逐个写入账户元数据
        for (AccountMeta account : accounts) {
            byte[] publicKey = account.getPublicKey();
            Objects.requireNonNull(publicKey, "账户公钥不能为空");
            if (publicKey.length != 32) {
                throw new IllegalArgumentException("账户公钥必须为32字节，实际为" + publicKey.length + "字节");
            }
            // 写入公钥（32字节）
            dos.write(publicKey);
            // 写入isSigner标志（1字节，0=false，1=true）
            dos.write(account.isSigner() ? 1 : 0);
            // 写入isWritable标志（1字节，0=false，1=true）
            dos.write(account.isWritable() ? 1 : 0);
        }
    }

    /**
     * 序列化最近区块哈希
     * 格式：[区块哈希(32字节)]
     */
    private void serializeRecentBlockhash(DataOutputStream dos) throws IOException {
        Objects.requireNonNull(recentBlockhash, "最近区块哈希不能为空");
        byte[] blockHashBytes = recentBlockhash.getBytes();
        if (blockHashBytes.length != 32) {
            throw new IllegalArgumentException("区块哈希必须为32字节，实际为" + blockHashBytes.length + "字节");
        }
        dos.write(blockHashBytes);
    }

    /**
     * 序列化指令列表
     * 格式：[指令数量(变长整数)] + [每个指令的序列化数据]
     * 每个指令数据：[程序ID索引(变长整数)] + [账户索引列表(变长整数数组)] + [指令数据长度(变长整数)] + [指令数据]
     */
    private void serializeInstructions(DataOutputStream dos) throws IOException {
        Objects.requireNonNull(instructions, "指令列表不能为空");
        // 写入指令数量（变长整数）
        dos.writeInt(instructions.size());

        // 逐个写入指令
        for (int i = 0; i < instructions.size(); i++) {
            Instruction instruction = instructions.get(i);
            // 程序ID索引：指向accounts列表中程序账户的位置（变长整数）
            writeVarInt(dos, instruction.getProgramIdIndex());
            // 账户索引列表：该指令涉及的账户在accounts中的索引（变长整数数组）
            List<Integer> accountIndices  = instruction.getAccounts();
            Objects.requireNonNull(accountIndices, "指令的账户索引列表不能为空");
            writeVarInt(dos, accountIndices.size()); // 索引数量
            for (int index : accountIndices) {
                writeVarInt(dos, index);
            }
            // 指令数据：二进制参数（长度+数据）
            byte[] data = instruction.getData();
            Objects.requireNonNull(data, "指令数据不能为空");
            writeVarInt(dos, data.length); // 数据长度（变长整数）
            dos.write(data); // 数据内容
        }
    }


    /**
     * 写入Solana风格的变长整数（VarInt）
     * 编码规则：每个字节的最高位表示是否继续（1=有后续字节，0=结束），低7位表示数据
     * 参考：https://docs.solana.com/developing/programming-model/transactions#varint
     */
    private void writeVarInt(DataOutputStream dos, int value) throws IOException {
        if (value < 0) {
            throw new IllegalArgumentException("变长整数不能为负数：" + value);
        }
        while (value >= 0x80) { // 0x80 = 128，需要多字节存储
            dos.write((value & 0x7F) | 0x80); // 低7位数据 + 最高位1（表示继续）
            value >>>= 7; // 右移7位处理下一组数据
        }
        dos.write(value & 0x7F); // 最后一个字节（最高位0）
    }



    @Override
    public int hashCode() {
        // 优先使用txId计算哈希（唯一标识）
        if (txId != null) {
            return calculateHashCodeFromBytes(txId);
        }

        // 若txId未生成（未签名），使用核心字段组合计算
        int result = 17; // 初始质数
        // 签名列表哈希
        result = 31 * result + (signatures != null ? signatures.hashCode() : 0);
        // 账户列表哈希
        result = 31 * result + (accounts != null ? accounts.hashCode() : 0);
        // 指令列表哈希
        result = 31 * result + (instructions != null ? instructions.hashCode() : 0);
        // 最近区块哈希
        result = 31 * result + (recentBlockhash != null ? recentBlockhash.hashCode() : 0);
        return result;
    }

    /**
     * 基于字节数组计算哈希值（适配txId的32字节数组）
     */
    private int calculateHashCodeFromBytes(byte[] bytes) {
        int hash = 0;
        // 取字节数组的前4个字节（32位）作为基础哈希，若不足4字节则全部参与计算
        for (int i = 0; i < Math.min(bytes.length, 4); i++) {
            hash = (hash << 8) | (bytes[i] & 0xFF); // 拼接字节为整数
        }
        // 结合数组整体长度和部分字节，增强哈希分布（避免前4字节相同的情况）
        hash ^= bytes.length;
        for (int i = bytes.length / 2; i < Math.min(bytes.length, bytes.length / 2 + 4); i++) {
            hash = 31 * hash + (bytes[i] & 0xFF);
        }
        // 确保哈希值非负（与SEGMENT_COUNT取模时结果为非负）
        return Math.abs(hash);
    }

    public int getSize() {
        return 10000;
    }



    /**
     * 判断交易是否过期
     * 规则：当前时间与交易提交时间的差值超过400ms，则认为过期
     * @param currentTime 当前时间（毫秒级时间戳，建议使用System.currentTimeMillis()）
     * @return 过期返回true，否则返回false
     */
    public boolean isExpired(long currentTime) {
        if (submitTime <= 0) {
            return true;
        }
        return (currentTime - submitTime) > 400;
    }
}