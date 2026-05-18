package com.bit.solana.blockchain.impl;

import com.bit.solana.blockchain.BlockChain;
import com.bit.solana.database.rocksDb.LedgerRocksStore;
import com.bit.solana.structure.vo.LedgerBlockVO;

public class BlockChainImpl implements BlockChain {
    private final LedgerRocksStore store;

    public BlockChainImpl(LedgerRocksStore store) {
        this.store = store;
    }

    @Override
    public LedgerBlockVO getLatestBlock() {
        return store.getLatestBlock();
    }

    @Override
    public LedgerBlockVO getBlockById(String blockId) {
        return store.getBlock(blockId);
    }

    @Override
    public LedgerBlockVO getBlockByHeight(long height) {
        return store.getBlockByHeight(height);
    }

    @Override
    public LedgerBlockVO getBlockBySlot(long slot) {
        return store.getBlockBySlot(slot);
    }
}
