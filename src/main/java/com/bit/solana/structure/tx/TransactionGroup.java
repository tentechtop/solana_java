package com.bit.solana.structure.tx;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

// 交易组（按账户分组，确保同一账户交易串行处理）
@Data
@AllArgsConstructor
@NoArgsConstructor
public class TransactionGroup {

    private byte[] groupId;             // 组ID（通常为核心账户地址）

    private List<Transaction> transactions;  // 组内交易

    public TransactionGroup(byte[] groupId) {
        this.groupId = groupId;
        this.transactions = new ArrayList<>();
    }
}
