package com.bit.solana.poh;

import com.bit.solana.structure.vo.LedgerTransactionVO;
import com.bit.solana.structure.vo.LeaderSlotVO;
import com.bit.solana.structure.vo.PohStatusVO;
import com.bit.solana.structure.vo.RecentBlockhashVO;
import com.bit.solana.structure.vo.TransactionResultVO;

import java.util.List;

public interface POHEngine {
    void start();

    void stop();

    long getCurrentSlot();

    String getRecentBlockhash();

    RecentBlockhashVO getRecentBlockhashInfo();

    boolean isRecentBlockhashValid(String recentBlockhash);

    LeaderSlotVO getLeaderSlot();

    void recordTransactions(List<LedgerTransactionVO> transactions, List<TransactionResultVO> results);

    PohStatusVO getStatus();
}
