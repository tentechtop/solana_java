package com.bit.solana.structure.vo;

public record PohStatusVO(
        long currentSlot,
        long currentTickInSlot,
        long latestBlockHeight,
        String currentHash,
        RecentBlockhashVO recentBlockhash,
        LeaderSlotVO leaderSlot,
        int ticksPerSlot
) {
}
