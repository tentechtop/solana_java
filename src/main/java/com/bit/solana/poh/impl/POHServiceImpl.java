package com.bit.solana.poh.impl;

import com.bit.solana.poh.POHEngine;
import com.bit.solana.poh.POHService;
import com.bit.solana.structure.vo.PohStatusVO;

public class POHServiceImpl implements POHService {
    private final POHEngine pohEngine;

    public POHServiceImpl(POHEngine pohEngine) {
        this.pohEngine = pohEngine;
    }

    @Override
    public PohStatusVO getStatus() {
        return pohEngine.getStatus();
    }
}
