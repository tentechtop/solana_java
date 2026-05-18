package com.bit.solana.test;

import com.bit.solana.common.BlockHash;
import com.bit.solana.common.Pubkey;
import com.bit.solana.common.PubkeyHash;

import com.bit.solana.structure.account.Account;
import com.bit.solana.structure.account.AccountMeta;
import com.bit.solana.structure.key.KeyInfo;
import com.bit.solana.structure.tx.Instruction;
import com.bit.solana.structure.tx.Signature;
import com.bit.solana.structure.tx.Transaction;
import com.bit.solana.util.ByteUtils;
import com.bit.solana.util.Sha;
import org.junit.jupiter.api.Test;

import java.security.SignatureException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.bit.solana.util.Ed25519HDWallet.generateMnemonic;
import static com.bit.solana.util.Ed25519HDWallet.getSolanaKeyPair;
import static com.bit.solana.util.SolanaEd25519Signer.fastSign;
import static com.bit.solana.util.SolanaEd25519Signer.fastVerify;

/**
 * Solana交易执行案例
 * 
 * 演示一笔完整的Solana交易从创建到执行的全过程,包括:
 * 1. 交易结构组成(签名列表、账户元数据列表、指令列表)
 * 2. 签名生成和验证过程
 * 3. 交易序列化过程
 * 4. 指令的执行逻辑
 * 
 * Solana交易核心概念:
 * - 交易由一个或多个指令组成
 * - 每个指令由特定的程序(智能合约)执行
 * - 交易需要支付者签名(第一个 isSigner=true && isWritable=true 的账户)
 * - 签名是对交易核心内容的哈希进行加密
 * - 最近区块哈希用于防重放攻击
 */
public class TransactionExecutionCase {

    /**
     * 案例1: 简单转账交易
     * 
     * 场景: Alice 向 Bob 转账 1000 lamports
     * 
     * 交易结构:
     * 1. 账户元数据列表:
     *    - [0] Alice账户 (isSigner=true, isWritable=true) - 支付者
     *    - [1] Bob账户   (isSigner=false, isWritable=true) - 接收者
     *    - [2] 系统程序  (isSigner=false, isWritable=false) - 执行程序
     * 
     * 2. 签名列表:
     *    - [0] Alice的签名 (证明Alice授权此交易)
     * 
     * 3. 指令列表:
     *    - [0] 系统转账指令 (programIdIndex=2, 使用账户[0,1], data包含转账金额)
     */
    @Test
    public void testSimpleTransferTransaction() throws Exception {
        System.out.println("========== 案例1: 简单转账交易 ==========\n");

        // ==================== 步骤1: 创建账户密钥对 ====================
        KeyInfo aliceKeyPair = getSolanaKeyPair(generateMnemonic(), 0, 0);
        KeyInfo bobKeyPair = getSolanaKeyPair(generateMnemonic(), 0, 0);
        KeyInfo systemProgramKeyPair = getSolanaKeyPair(generateMnemonic(), 0, 0);


        System.out.println("【步骤1】创建账户密钥对:");
        System.out.println("Alice公钥: " + ByteUtils.bytesToHex(aliceKeyPair.getPublicKey() ));
        System.out.println("Bob公钥:   " + ByteUtils.bytesToHex(bobKeyPair.getPublicKey()));
        System.out.println("系统程序公钥: " + ByteUtils.bytesToHex(systemProgramKeyPair.getPublicKey()));
        System.out.println();

        // ==================== 步骤2: 构建账户元数据列表 ====================
        List<AccountMeta> accounts = new ArrayList<>();

        // Alice账户: 需要签名, 可写(余额会减少)
        AccountMeta aliceAccountMeta = new AccountMeta();
        aliceAccountMeta.setPubkey(new PubkeyHash(aliceKeyPair.getPublicKey()));
        aliceAccountMeta.setSigner(true);  // Alice需要签名
        aliceAccountMeta.setWritable(true); // Alice余额会改变
        accounts.add(aliceAccountMeta);

        // Bob账户: 不需要签名, 可写(余额会增加)
        AccountMeta bobAccountMeta = new AccountMeta();
        bobAccountMeta.setPubkey(new PubkeyHash(bobKeyPair.getPublicKey()));
        bobAccountMeta.setSigner(false);  // Bob不需要签名
        bobAccountMeta.setWritable(true); // Bob余额会改变
        accounts.add(bobAccountMeta);

        // 系统程序账户: 不需要签名, 不可写
        AccountMeta systemProgramMeta = new AccountMeta();
        systemProgramMeta.setPubkey(new PubkeyHash(systemProgramKeyPair.getPublicKey()));
        systemProgramMeta.setSigner(false); // 程序不需要签名
        systemProgramMeta.setWritable(false); // 程序代码不可修改
        accounts.add(systemProgramMeta);

        System.out.println("【步骤2】构建账户元数据列表:");
        System.out.println("[0] Alice账户: isSigner=" + aliceAccountMeta.isSigner() + ", isWritable=" + aliceAccountMeta.isWritable());
        System.out.println("[1] Bob账户:   isSigner=" + bobAccountMeta.isSigner() + ", isWritable=" + bobAccountMeta.isWritable());
        System.out.println("[2] 系统程序:  isSigner=" + systemProgramMeta.isSigner() + ", isWritable=" + systemProgramMeta.isWritable());
        System.out.println("=> 支付者确认: " + ByteUtils.bytesToHex(getPayerPublicKey(accounts)));
        System.out.println();

        // ==================== 步骤3: 构建指令列表 ====================
        List<Instruction> instructions = new ArrayList<>();

        // 构建系统转账指令
        Instruction transferInstruction = new Instruction();
        transferInstruction.setProgramId(systemProgramKeyPair.getPublicKey());
        transferInstruction.setProgramIdIndex(2); // 指向accounts[2] - 系统程序
        
        // 该指令涉及的账户索引: Alice(0) 和 Bob(1)
        List<Integer> transferAccounts = Arrays.asList(0, 1);
        transferInstruction.setAccounts(transferAccounts);
        
        // 构建指令数据: 转账金额 1000 lamports (8字节, 小端序)
        byte[] transferData = new byte[12]; // 4字节指令类型 + 8字节金额
        transferData[0] = 2; // 指令类型: 2表示转账
        // 转账金额 1000 lamports, 小端序存储
        long amount = 1000L;
        for (int i = 0; i < 8; i++) {
            transferData[4 + i] = (byte) (amount >>> (i * 8));
        }
        transferInstruction.setData(transferData);

        instructions.add(transferInstruction);

        System.out.println("【步骤3】构建指令列表:");
        System.out.println("[0] 系统转账指令:");
        System.out.println("    - programIdIndex: " + transferInstruction.getProgramIdIndex() + " (指向系统程序)");
        System.out.println("    - 使用账户: " + transferInstruction.getAccounts());
        System.out.println("    - 指令数据: " + ByteUtils.bytesToHex(transferInstruction.getData()));
        System.out.println("    - 转账金额: " + amount + " lamports");
        System.out.println();

        // ==================== 步骤4: 设置最近区块哈希 ====================
        byte[] blockhashBytes = new byte[32];
        Arrays.fill(blockhashBytes, (byte) 0x12); // 模拟区块哈希
        BlockHash recentBlockhash = new BlockHash(blockhashBytes);

        System.out.println("【步骤4】设置最近区块哈希:");
        System.out.println("区块哈希: " + ByteUtils.bytesToHex(recentBlockhash.getBytes()));
        System.out.println("作用: 防重放攻击, 控制交易有效期(约2分钟)");
        System.out.println();

        // ==================== 步骤5: 创建交易对象(不带签名) ====================
        Transaction transaction = new Transaction();
        transaction.setAccounts(accounts);
        transaction.setInstructions(instructions);
        transaction.setRecentBlockhash(recentBlockhash);

        System.out.println("【步骤5】创建交易对象(未签名):");
        System.out.println("账户数量: " + transaction.getAccounts().size());
        System.out.println("指令数量: " + transaction.getInstructions().size());
        System.out.println();

        // ==================== 步骤6: 构建待签名数据 ====================
        byte[] signData = transaction.buildSignData();
        
        System.out.println("【步骤6】构建待签名数据:");
        System.out.println("待签名数据长度: " + signData.length + " 字节");
        System.out.println("待签名数据(Hex前64字节): " + ByteUtils.bytesToHex(Arrays.copyOfRange(signData, 0, Math.min(64, signData.length))));
        System.out.println();

        // ==================== 步骤7: 计算交易哈希 ====================
        byte[] transactionHash = Sha.applySHA256(signData);
        
        System.out.println("【步骤7】计算交易哈希(SHA256):");
        System.out.println("交易哈希: " + ByteUtils.bytesToHex(transactionHash));
        System.out.println();

        // ==================== 步骤8: 生成签名 ====================
        List<Signature> signatures = new ArrayList<>();
        
        // Alice用私钥签名
        byte[] bytes = fastSign(aliceKeyPair.getPrivateKey(), transactionHash);
        Signature aliceSignature = new Signature();
        aliceSignature.setValue(bytes);
        signatures.add(aliceSignature);

        System.out.println("【步骤8】生成签名:");
        System.out.println("Alice签名: " + ByteUtils.bytesToHex(aliceSignature.getValue()));
        System.out.println("签名长度: " + aliceSignature.getValue().length + " 字节 (Ed25519标准)");
        System.out.println();

        // ==================== 步骤9: 添加签名到交易 ====================
        transaction.setSignatures(signatures);

        System.out.println("【步骤9】添加签名到交易:");
        System.out.println("签名数量: " + transaction.getSignatures().size());
        System.out.println();

        // ==================== 步骤10: 验证签名 ====================
        boolean signatureValid = fastVerify(aliceKeyPair.getPublicKey(), transactionHash, aliceSignature.getValue());
        
        System.out.println("【步骤10】验证签名:");
        System.out.println("Alice签名验证结果: " + (signatureValid ? "✓ 有效" : "✗ 无效"));
        System.out.println();

        // ==================== 步骤11: 生成交易ID ====================
        String txId = transaction.getTxIdStr();
        
        System.out.println("【步骤11】生成交易ID:");
        System.out.println("交易ID: " + txId);
        System.out.println("(交易ID本质是第一个签名的SHA256哈希)");
        System.out.println();

        // ==================== 步骤12: 模拟指令执行 ====================
        System.out.println("【步骤12】模拟指令执行:");
        simulateTransactionExecution(transaction, aliceKeyPair, bobKeyPair);
        System.out.println();

        // ==================== 交易结构总结 ====================
        System.out.println("========== 交易结构总结 ==========");
        printTransactionSummary(transaction);
    }

    /**
     * 案例2: 多指令交易
     * 
     * 场景: Alice在一个交易中执行多个操作:
     * 1. 向Bob转账500 lamports
     * 2. 向Charlie转账300 lamports
     * 
     * 优势: 原子性执行 - 所有指令要么全部成功,要么全部失败
     */
    @Test
    public void testMultiInstructionTransaction() throws Exception {
        System.out.println("\n========== 案例2: 多指令交易 ==========\n");
        KeyInfo aliceKeyPair = getSolanaKeyPair(generateMnemonic(), 0, 0);
        KeyInfo bobKeyPair = getSolanaKeyPair(generateMnemonic(), 0, 0);
        KeyInfo charlieKeyPair = getSolanaKeyPair(generateMnemonic(), 0, 0);
        KeyInfo systemProgramKeyPair = getSolanaKeyPair(generateMnemonic(), 0, 0);



        System.out.println("账户信息:");
        System.out.println("Alice:   " + ByteUtils.bytesToHex(aliceKeyPair.getPublicKey()));
        System.out.println("Bob:     " + ByteUtils.bytesToHex(bobKeyPair.getPublicKey()));
        System.out.println("Charlie: " + ByteUtils.bytesToHex(charlieKeyPair.getPublicKey()));
        System.out.println();

        // 构建账户元数据列表
        List<AccountMeta> accounts = new ArrayList<>();
        
        AccountMeta aliceMeta = createAccountMeta(aliceKeyPair, true, true);
        AccountMeta bobMeta = createAccountMeta(bobKeyPair, false, true);
        AccountMeta charlieMeta = createAccountMeta(charlieKeyPair, false, true);
        AccountMeta systemMeta = createAccountMeta(systemProgramKeyPair, false, false);
        
        accounts.add(aliceMeta);
        accounts.add(bobMeta);
        accounts.add(charlieMeta);
        accounts.add(systemMeta);

        System.out.println("账户元数据列表:");
        for (int i = 0; i < accounts.size(); i++) {
            AccountMeta meta = accounts.get(i);
            String name = i == 0 ? "Alice" : i == 1 ? "Bob" : i == 2 ? "Charlie" : "System";
            System.out.println("[" + i + "] " + name + ": isSigner=" + meta.isSigner() + ", isWritable=" + meta.isWritable());
        }
        System.out.println();

        // 构建指令列表
        List<Instruction> instructions = new ArrayList<>();

        // 指令1: Alice -> Bob 转账500 lamports
        Instruction instruction1 = createTransferInstruction(systemProgramKeyPair, 3, Arrays.asList(0, 1), 500L);
        instructions.add(instruction1);

        // 指令2: Alice -> Charlie 转账300 lamports
        Instruction instruction2 = createTransferInstruction(systemProgramKeyPair, 3, Arrays.asList(0, 2), 300L);
        instructions.add(instruction2);

        System.out.println("指令列表:");
        System.out.println("[0] 转账指令: Alice -> Bob 500 lamports");
        System.out.println("[1] 转账指令: Alice -> Charlie 300 lamports");
        System.out.println("原子性: 所有指令要么全部成功,要么全部失败");
        System.out.println();

        // 创建交易并签名
        BlockHash recentBlockhash = new BlockHash(generateMockBlockhash());
        
        Transaction transaction = new Transaction();
        transaction.setAccounts(accounts);
        transaction.setInstructions(instructions);
        transaction.setRecentBlockhash(recentBlockhash);

        // 签名
        byte[] signData = transaction.buildSignData();
        byte[] txHash = Sha.applySHA256(signData);
        byte[] bytes = fastSign(aliceKeyPair.getPrivateKey(), txHash);
        Signature aliceSignature = new  Signature();
        aliceSignature.setValue(bytes);
        transaction.setSignatures(Arrays.asList(aliceSignature));

        System.out.println("交易ID: " + transaction.getTxIdStr());
        System.out.println("签名数量: " + transaction.getSignatures().size());
        System.out.println();

        printTransactionSummary(transaction);
    }

    /**
     * 案例3: 交易签名验证演示
     * 
     * 演示:
     * 1. 签名数据的内容
     * 2. 签名的生成过程
     * 3. 签名的验证过程
     * 4. 错误签名的检测
     */
    @Test
    public void testSignatureVerification() throws Exception {
        System.out.println("\n========== 案例3: 交易签名验证 ==========\n");

        // 创建密钥对
        KeyInfo aliceKeyPair = getSolanaKeyPair(generateMnemonic(), 0, 0);
        KeyInfo bobKeyPair = getSolanaKeyPair(generateMnemonic(), 0, 0);
        KeyInfo systemProgramKeyPair = getSolanaKeyPair(generateMnemonic(), 0, 0);


        // 构建交易
        List<AccountMeta> accounts = Arrays.asList(
            createAccountMeta(aliceKeyPair, true, true),
            createAccountMeta(bobKeyPair, false, true),
            createAccountMeta(systemProgramKeyPair, false, false)
        );

        List<Instruction> instructions = Arrays.asList(
            createTransferInstruction(systemProgramKeyPair, 2, Arrays.asList(0, 1), 1000L)
        );

        BlockHash recentBlockhash = new BlockHash(generateMockBlockhash());
        
        Transaction transaction = new Transaction();
        transaction.setAccounts(accounts);
        transaction.setInstructions(instructions);
        transaction.setRecentBlockhash(recentBlockhash);

        System.out.println("【原始交易】");
        printTransactionStructure(transaction);

        // 生成签名
        byte[] signData = transaction.buildSignData();
        System.out.println("【签名数据】");
        System.out.println("签名数据(Hex): " + ByteUtils.bytesToHex(signData));
        System.out.println("签名数据长度: " + signData.length + " 字节");
        System.out.println();

        byte[] txHash = Sha.applySHA256(signData);
        System.out.println("交易哈希(SHA256): " + ByteUtils.bytesToHex(txHash));
        System.out.println();

        // Alice签名
        byte[] bytes = fastSign(aliceKeyPair.getPrivateKey(), txHash);
        Signature validSignature = new Signature();
        validSignature.setValue(bytes);
        System.out.println("【Alice的签名】");
        System.out.println("签名(Hex): " + ByteUtils.bytesToHex(validSignature.getValue()));
        System.out.println();

        // 验证Alice的签名
        boolean isValid = fastVerify(aliceKeyPair.getPublicKey(), txHash, validSignature.getValue());
        System.out.println("【签名验证】");
        System.out.println("Alice公钥验证: " + (isValid ? "✓ 有效" : "✗ 无效"));
        System.out.println();

        // 尝试用Bob的公钥验证Alice的签名 (应该失败)
        boolean isBobValid = fastVerify(bobKeyPair.getPublicKey(), txHash, validSignature.getValue());
        System.out.println("Bob公钥验证(应该失败): " + (isBobValid ? "✓ 有效(异常)" : "✗ 无效(正确)"));
        System.out.println();

        // 伪造签名验证 (用错误的数据)
        byte[] wrongHash = Sha.applySHA256(new byte[] {1, 2, 3, 4});
        byte[] bytes1 = fastSign(aliceKeyPair.getPrivateKey(), wrongHash);
        Signature wrongSignature = new Signature();
        wrongSignature.setValue(bytes1);
        boolean isWrongValid = fastVerify(aliceKeyPair.getPublicKey(), txHash, wrongSignature.getValue());
        System.out.println("错误签名验证(应该失败): " + (isWrongValid ? "✓ 有效(异常)" : "✗ 无效(正确)"));
        System.out.println();

        System.out.println("【签名验证结论】");
        System.out.println("1. 只有原始签名者的公钥才能验证签名");
        System.out.println("2. 签名必须对正确的交易哈希进行验证");
        System.out.println("3. 篡改交易内容会导致签名验证失败");
    }

    // ==================== 辅助方法 ====================




    /**
     * 创建账户元数据
     */
    private AccountMeta createAccountMeta(KeyInfo keyPair, boolean isSigner, boolean isWritable) {
        AccountMeta meta = new AccountMeta();
        meta.setPubkey(new PubkeyHash(keyPair.getPublicKey()));
        meta.setSigner(isSigner);
        meta.setWritable(isWritable);
        return meta;
    }

    /**
     * 创建转账指令
     */
    private Instruction createTransferInstruction(KeyInfo systemProgram,
                                                   int programIdIndex, 
                                                   List<Integer> accountIndices,
                                                   long amount) {
        Instruction instruction = new Instruction();
        instruction.setProgramId(systemProgram.getPublicKey());
        instruction.setProgramIdIndex(programIdIndex);
        instruction.setAccounts(accountIndices);
        
        // 构建指令数据
        byte[] data = new byte[12];
        data[0] = 2; // 指令类型: 2表示转账
        for (int i = 0; i < 8; i++) {
            data[4 + i] = (byte) (amount >>> (i * 8));
        }
        instruction.setData(data);
        
        return instruction;
    }

    /**
     * 生成模拟区块哈希
     */
    private byte[] generateMockBlockhash() {
        byte[] blockhash = new byte[32];
        for (int i = 0; i < 32; i++) {
            blockhash[i] = (byte) (System.currentTimeMillis() % 256);
        }
        return blockhash;
    }

    /**
     * 获取支付者公钥
     */
    private byte[] getPayerPublicKey(List<AccountMeta> accounts) {
        for (AccountMeta account : accounts) {
            if (account.isSigner() && account.isWritable()) {
                return account.getPublicKey();
            }
        }
        throw new IllegalArgumentException("未找到支付者账户");
    }

    /**
     * 模拟交易执行过程
     */
    private void simulateTransactionExecution(Transaction transaction,
                                               KeyInfo aliceKeyPair,
                                              KeyInfo bobKeyPair) {
        System.out.println("--- 执行前置检查 ---");
        
        // 1. 检查签名
        if (transaction.getSignatures() == null || transaction.getSignatures().isEmpty()) {
            System.out.println("✗ 交易缺少签名");
            return;
        }
        System.out.println("✓ 签名检查通过");
        
        // 2. 检查账户
        if (transaction.getAccounts() == null || transaction.getAccounts().isEmpty()) {
            System.out.println("✗ 交易缺少账户");
            return;
        }
        System.out.println("✓ 账户检查通过");
        
        // 3. 检查指令
        if (transaction.getInstructions() == null || transaction.getInstructions().isEmpty()) {
            System.out.println("✗ 交易缺少指令");
            return;
        }
        System.out.println("✓ 指令检查通过");
        
        // 4. 检查区块哈希
        if (transaction.getRecentBlockhash() == null) {
            System.out.println("✗ 交易缺少区块哈希");
            return;
        }
        System.out.println("✓ 区块哈希检查通过");
        
        System.out.println();
        System.out.println("--- 开始执行指令 ---");
        
        // 执行每个指令
        for (int i = 0; i < transaction.getInstructions().size(); i++) {
            Instruction instruction = transaction.getInstructions().get(i);
            System.out.println("执行指令[" + i + "]:");
            
            // 获取程序账户
            int programIdIndex = instruction.getProgramIdIndex();
            AccountMeta programMeta = transaction.getAccounts().get(programIdIndex);
            System.out.println("  执行程序: " + ByteUtils.bytesToHex(programMeta.getPublicKey()));
            
            // 获取指令涉及的账户
            System.out.println("  涉及账户索引: " + instruction.getAccounts());
            
            // 解析指令数据
            byte[] data = instruction.getData();
            if (data.length >= 4 && data[0] == 2) {
                // 转账指令
                long amount = 0;
                for (int j = 0; j < 8; j++) {
                    amount |= (data[4 + j] & 0xFFL) << (j * 8);
                }
                System.out.println("  指令类型: 转账");
                System.out.println("  转账金额: " + amount + " lamports");
                
                // 模拟转账
                List<Integer> accounts = instruction.getAccounts();
                int fromIndex = accounts.get(0);
                int toIndex = accounts.get(1);
                String fromName = fromIndex == 0 ? "Alice" : "Unknown";
                String toName = toIndex == 1 ? "Bob" : "Unknown";
                System.out.println("  转账路径: " + fromName + " -> " + toName);
                System.out.println("  ✓ 转账执行成功");
            } else {
                System.out.println("  指令类型: 未知");
            }
            
            System.out.println();
        }
        
        System.out.println("--- 交易执行完成 ---");
        System.out.println("状态: 所有指令执行成功");
    }

    /**
     * 打印交易结构
     */
    private void printTransactionStructure(Transaction transaction) {
        System.out.println("账户数量: " + transaction.getAccounts().size());
        for (int i = 0; i < transaction.getAccounts().size(); i++) {
            AccountMeta meta = transaction.getAccounts().get(i);
            System.out.println("  [" + i + "] " + ByteUtils.bytesToHex(meta.getPublicKey()) + 
                             " (signer=" + meta.isSigner() + ", writable=" + meta.isWritable() + ")");
        }
        
        System.out.println("指令数量: " + transaction.getInstructions().size());
        for (int i = 0; i < transaction.getInstructions().size(); i++) {
            Instruction instr = transaction.getInstructions().get(i);
            System.out.println("  [" + i + "] programIdIndex=" + instr.getProgramIdIndex() + 
                             ", accounts=" + instr.getAccounts() + 
                             ", data=" + ByteUtils.bytesToHex(instr.getData()));
        }
        
        System.out.println("区块哈希: " + ByteUtils.bytesToHex(transaction.getRecentBlockhash().getBytes()));
    }

    /**
     * 打印交易总结
     */
    private void printTransactionSummary(Transaction transaction) {
        System.out.println("┌─────────────────────────────────────────────────┐");
        System.out.println("│              Solana交易结构总结                  │");
        System.out.println("├─────────────────────────────────────────────────┤");
        System.out.println("│ 交易ID: " + transaction.getTxIdStr());
        System.out.println("│");
        System.out.println("│ 【签名列表】 (" + transaction.getSignatures().size() + "个)");
        for (int i = 0; i < transaction.getSignatures().size(); i++) {
            Signature sig = transaction.getSignatures().get(i);
            System.out.println("│   [" + i + "] " + ByteUtils.bytesToHex(sig.getValue()).substring(0, 32) + "...");
        }
        System.out.println("│");
        System.out.println("│ 【账户元数据列表】 (" + transaction.getAccounts().size() + "个)");
        for (int i = 0; i < transaction.getAccounts().size(); i++) {
            AccountMeta meta = transaction.getAccounts().get(i);
            String type = meta.isSigner() && meta.isWritable() ? " [支付者]" : "";
            System.out.println("│   [" + i + "] " + 
                             (meta.isSigner() ? "✓" : " ") + " " +
                             (meta.isWritable() ? "W" : "R") + " " +
                             ByteUtils.bytesToHex(meta.getPublicKey()).substring(0, 16) + "..." + type);
        }
        System.out.println("│");
        System.out.println("│ 【指令列表】 (" + transaction.getInstructions().size() + "个)");
        for (int i = 0; i < transaction.getInstructions().size(); i++) {
            Instruction instr = transaction.getInstructions().get(i);
            System.out.println("│   [" + i + "] ProgramIndex=" + instr.getProgramIdIndex() + 
                             ", Accounts=" + instr.getAccounts());
        }
        System.out.println("│");
        System.out.println("│ 【最近区块哈希】");
        System.out.println("│   " + ByteUtils.bytesToHex(transaction.getRecentBlockhash().getBytes()).substring(0, 32) + "...");
        System.out.println("└─────────────────────────────────────────────────┘");
    }
}
