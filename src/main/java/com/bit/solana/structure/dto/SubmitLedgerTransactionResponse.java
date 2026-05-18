package com.bit.solana.structure.dto;

public record SubmitLedgerTransactionResponse(
        String transactionId,
        String status,
        String recentBlockHash,
        long lastValidSlot
) {
}
