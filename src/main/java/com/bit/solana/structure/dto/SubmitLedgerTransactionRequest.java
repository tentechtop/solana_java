package com.bit.solana.structure.dto;

public record SubmitLedgerTransactionRequest(
        String fromAccountId,
        String toAccountId,
        long lamports,
        String privateKey
) {
}
