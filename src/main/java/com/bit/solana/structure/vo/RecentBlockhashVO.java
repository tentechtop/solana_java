package com.bit.solana.structure.vo;

public record RecentBlockhashVO(
        String blockhash,
        long slot,
        long blockHeight,
        long lastValidSlot,
        long registeredAt
) {
}
