package com.bit.solana.structure.tx.json;

import com.bit.solana.structure.account.AccountMeta;
import com.bit.solana.structure.account.json.AccountMetaJson;
import com.bit.solana.structure.tx.Instruction;
import com.bit.solana.structure.tx.Signature;
import lombok.Data;

import java.math.BigInteger;
import java.util.List;

@Data
public class TransferTx {
    private String privateKey;       // Hex格式私钥
    private String fromAddress;      // Hex格式公钥
    private String toAddress;        // Hex格式公钥（或合约地址）
    private BigInteger amount;       // Lamports
    private String instruction;      // 合约指令（如"transfer"）
    private String instructionData;  // 指令参数（JSON字符串）
    private List<String> relatedAccounts; // Hex格式公钥列表
    private String recentBlockhash;  // Hex格式区块哈希
    private String signature;        // Hex格式签名
}
