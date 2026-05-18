package com.bit.solana.poh.impl;

import com.bit.solana.config.NodeProperties;
import com.bit.solana.structure.vo.LeaderSlotVO;

class LeaderSchedule {
    private final String localValidatorIdentity;

    LeaderSchedule(NodeProperties properties) {
        String configuredIdentity = properties.getValidatorIdentity();
        this.localValidatorIdentity = configuredIdentity == null || configuredIdentity.isBlank()
                ? "validator-local"
                : configuredIdentity;
    }

    LeaderSlotVO slotLeader(long slot) {
        return new LeaderSlotVO(slot, localValidatorIdentity, localValidatorIdentity, true);
    }
}
