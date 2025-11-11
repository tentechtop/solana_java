package com.bit.solana.structure.tx.json;

import com.bit.solana.structure.account.AccountMeta;
import com.bit.solana.structure.account.json.AccountMetaJson;
import com.bit.solana.structure.tx.Instruction;
import com.bit.solana.structure.tx.Signature;
import lombok.Data;

import java.util.List;

@Data
public class TransferTx {


    private List<String> signatures;

    private List<AccountMetaJson> accounts;

    /**
     * 指令列表（交易要执行的具体操作）
     * 每个指令由指定的程序（智能合约）处理
     */
    private List<Instruction> instructions;
}
