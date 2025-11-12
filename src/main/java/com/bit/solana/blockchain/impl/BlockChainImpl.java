package com.bit.solana.blockchain.impl;

import com.bit.solana.blockchain.BlockChain;
import com.bit.solana.result.Result;
import com.bit.solana.structure.block.Block;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class BlockChainImpl implements BlockChain {


    @Override
    public Result processBlock(Block block) {

        return Result.OK();
    }

    @Override
    public Block getLatestBlock() {
        return null;
    }

    @Override
    public void generateBlock(long currentHeight, byte[] clone) {

    }
}
