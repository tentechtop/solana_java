package com.bit.solana.blockchain;

import com.bit.solana.structure.vo.LedgerBlockVO;

public interface BlockChain {
    LedgerBlockVO getLatestBlock();

    LedgerBlockVO getBlockById(String blockId);

    LedgerBlockVO getBlockByHeight(long height);

    LedgerBlockVO getBlockBySlot(long slot);
}
