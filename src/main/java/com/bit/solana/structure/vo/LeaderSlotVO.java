package com.bit.solana.structure.vo;

public record LeaderSlotVO(
        long slot,
        String slotLeader,
        String localValidatorIdentity,
        boolean localLeader
) {
}
