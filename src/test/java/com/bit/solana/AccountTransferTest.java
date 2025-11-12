package com.bit.solana;

import com.bit.solana.common.BlockHash;
import com.bit.solana.common.Pubkey;
import com.bit.solana.common.PubkeyHash;
import com.bit.solana.structure.account.Account;
import com.bit.solana.structure.account.AccountMeta;
import com.bit.solana.structure.tx.Instruction;
import com.bit.solana.structure.tx.Signature;
import com.bit.solana.structure.tx.Transaction;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;



@Slf4j
public class AccountTransferTest {

    // 实际Solana系统程序公钥（固定值）
    private static final byte[] SYSTEM_PROGRAM_ID = new byte[]{85, 48, (byte) 140, (byte) 166, (byte) 222, 95, (byte) 210, 50, 127, (byte) 135, (byte) 189, 86, (byte) 149, (byte) 241, (byte) 205, 14, (byte) 139, (byte) 199, 120, (byte) 217, (byte) 157, 7, (byte) 158, 15, (byte) 130, 120, (byte) 175, 125, (byte) 202, (byte) 174, (byte) 129, 124};

    @Test
    void testAccountTransferWithDataExecution() {
        // 1. 创建两个测试账户（发送方和接收方公钥需不同）
        byte[] senderBytes = new byte[32];
        Arrays.fill(senderBytes, (byte) 0x01); // 发送方公钥（非全0）
        Pubkey senderPubkey = Pubkey.fromBytes(senderBytes);

        byte[] receiverBytes = new byte[32];
        Arrays.fill(receiverBytes, (byte) 0x02); // 接收方公钥（与发送方不同）
        Pubkey receiverPubkey = Pubkey.fromBytes(receiverBytes);

        Account sender = new Account();
        sender.setPubkey(senderPubkey);
        sender.setLamports(1000000); // 初始1,000,000 lamports
        sender.setOwner(new Pubkey(SYSTEM_PROGRAM_ID)); // 所有者为系统程序
        sender.setData(new byte[0]);
        sender.setExecutable(false);
        sender.setHasRentExemption(true);

        Account receiver = new Account();
        receiver.setPubkey(receiverPubkey);
        receiver.setLamports(500000); // 初始500,000 lamports
        receiver.setOwner(new Pubkey(SYSTEM_PROGRAM_ID));
        receiver.setData(new byte[0]);
        receiver.setExecutable(false);
        receiver.setHasRentExemption(true);

        // 2. 构建转账交易（包含待执行的字节码数据）
        long transferAmount = 200000; // 转账200,000 lamports
        byte[] transferData = longToBytes(transferAmount); // 转换为8字节数组（字节码）

        // 2.1 账户元数据（指定签名者和可写性）
        AccountMeta senderMeta = new AccountMeta();
        senderMeta.setPubkey(new PubkeyHash(senderPubkey.toBytes()));
        senderMeta.setSigner(true); // 发送方必须签名
        senderMeta.setWritable(true); // 余额会变化

        AccountMeta receiverMeta = new AccountMeta();
        receiverMeta.setPubkey(new PubkeyHash(receiverPubkey.toBytes()));
        receiverMeta.setSigner(false); // 接收方无需签名
        receiverMeta.setWritable(true); // 余额会变化

        List<AccountMeta> accountMetas = Arrays.asList(senderMeta, receiverMeta);

        // 2.2 转账指令（包含字节码数据）
        Instruction transferInstruction = new Instruction();
        transferInstruction.setProgramId(SYSTEM_PROGRAM_ID); // 系统程序处理
        transferInstruction.setData(transferData); // 关键：指令数据为转账金额的字节码

        // 2.3 模拟签名（实际需用发送方私钥签名）
        Signature signature = new Signature();
        signature.setValue(new byte[64]); // 测试用空签名（实际需验证有效性）

        // 2.4 完整交易
        Transaction transaction = new Transaction();
        transaction.setSignatures(Collections.singletonList(signature));
        transaction.setAccounts(accountMetas);
        transaction.setInstructions(Collections.singletonList(transferInstruction));
        transaction.setRecentBlockhash(new BlockHash(new byte[32]));

        // 3. 执行交易：解析字节码并处理转账（核心改进部分）
        long senderInitial = sender.getLamports();
        long receiverInitial = receiver.getLamports();

        // 3.1 模拟系统程序处理器（负责解析data字节码并执行转账）
        TransactionProcessor processor = new TransactionProcessor();
        try {
            processor.process(transaction, sender, receiver); // 传入实际账户进行处理
        } catch (Exception e) {
            fail("交易执行失败：" + e.getMessage());
        }

        // 4. 验证结果
        assertEquals(senderInitial - transferAmount, sender.getLamports(), "发送方余额扣除错误");
        assertEquals(receiverInitial + transferAmount, receiver.getLamports(), "接收方余额增加错误");
        log.info("转账成功：发送方剩余 {}，接收方剩余 {}", sender.getLamports(), receiver.getLamports());
    }

    // 模拟交易处理器：负责解析指令中的字节码并执行业务逻辑
    class TransactionProcessor {
        public void process(Transaction tx, Account... accounts) throws Exception {
            // 1. 验证程序ID（必须是系统程序）
            if (!Arrays.equals(tx.getInstructions().get(0).getProgramId(), SYSTEM_PROGRAM_ID)) {
                throw new Exception("无效的程序ID，必须使用系统程序");
            }

            // 2. 从指令中提取字节码数据（转账金额）
            Instruction instruction = tx.getInstructions().get(0);
            byte[] data = instruction.getData();
            if (data.length != 8) { // Solana转账指令数据固定为8字节（long类型）
                throw new Exception("无效的指令数据长度，预期8字节");
            }
            long transferAmount = bytesToLong(data); // 解析字节码为金额（核心步骤）

            // 3. 验证账户元数据（发送方必须是签名者且可写）
            AccountMeta senderMeta = tx.getAccounts().get(0);
            if (!senderMeta.isSigner() || !senderMeta.isWritable()) {
                throw new Exception("发送方账户必须是签名者且可写");
            }

            // 4. 执行转账（从字节码解析出的金额）
            Account sender = accounts[0];
            Account receiver = accounts[1];

            if (sender.getLamports() < transferAmount) {
                throw new Exception("发送方余额不足");
            }
            sender.setLamports(sender.getLamports() - transferAmount);
            receiver.setLamports(receiver.getLamports() + transferAmount);
        }

        // 辅助方法：将8字节数组转换为long（解析字节码）
        private long bytesToLong(byte[] bytes) {
            if (bytes.length != 8) {
                throw new IllegalArgumentException("字节数组必须为8字节");
            }
            long value = 0;
            for (int i = 0; i < 8; i++) {
                value <<= 8;
                value |= (bytes[i] & 0xFF);
            }
            return value;
        }
    }

    // 辅助方法：将long转换为8字节数组（生成字节码，符合Solana协议）
    private byte[] longToBytes(long value) {
        byte[] bytes = new byte[8];
        for (int i = 7; i >= 0; i--) {
            bytes[i] = (byte) (value & 0xFF);
            value >>= 8;
        }
        return bytes;
    }
}