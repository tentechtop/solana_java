package com.bit.solana.api;

import com.bit.solana.blockchain.BlockChain;
import com.bit.solana.result.Result;
import com.bit.solana.structure.vo.LedgerBlockVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/block")
public class BlockApi {
    private final BlockChain blockChain;

    public BlockApi(BlockChain blockChain) {
        this.blockChain = blockChain;
    }

    @GetMapping("/latest")
    public Result<LedgerBlockVO> latest() {
        LedgerBlockVO block = blockChain.getLatestBlock();
        return block == null ? Result.error("Latest block not found") : Result.OKData(block);
    }

    @GetMapping("/{blockId}")
    public Result<LedgerBlockVO> byId(@PathVariable String blockId) {
        LedgerBlockVO block = blockChain.getBlockById(blockId);
        return block == null ? Result.error("Block not found") : Result.OKData(block);
    }

    @GetMapping("/height/{height}")
    public Result<LedgerBlockVO> byHeight(@PathVariable long height) {
        LedgerBlockVO block = blockChain.getBlockByHeight(height);
        return block == null ? Result.error("Block not found") : Result.OKData(block);
    }

    @GetMapping("/slot/{slot}")
    public Result<LedgerBlockVO> bySlot(@PathVariable long slot) {
        LedgerBlockVO block = blockChain.getBlockBySlot(slot);
        return block == null ? Result.error("Block not found") : Result.OKData(block);
    }
}
