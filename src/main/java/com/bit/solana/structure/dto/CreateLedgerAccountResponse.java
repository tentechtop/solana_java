package com.bit.solana.structure.dto;

public record CreateLedgerAccountResponse(
        String accountId,
        String publicKey,
        String privateKey,
        long minimumRent,
        long lamports
) {
}
