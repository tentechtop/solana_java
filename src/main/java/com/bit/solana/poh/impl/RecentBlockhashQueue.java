package com.bit.solana.poh.impl;

import com.bit.solana.structure.vo.RecentBlockhashVO;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class RecentBlockhashQueue {
    private final int blockhashWindow;
    private final Deque<RecentBlockhashVO> queue = new ArrayDeque<>();
    private final Map<String, RecentBlockhashVO> byHash = new HashMap<>();

    RecentBlockhashQueue(int blockhashWindow) {
        this.blockhashWindow = Math.max(1, blockhashWindow);
    }

    void bootstrap(String genesisHash, List<RecentBlockhashVO> blockhashes) {
        queue.clear();
        byHash.clear();
        add(new RecentBlockhashVO(genesisHash, -1L, 0L, blockhashWindow - 1L, 0L));
        if (blockhashes == null) {
            return;
        }
        for (RecentBlockhashVO blockhash : blockhashes) {
            add(blockhash);
            prune(blockhash.slot());
        }
    }

    RecentBlockhashVO latest() {
        return queue.peekLast();
    }

    boolean isValid(String blockhash, long currentSlot) {
        if (blockhash == null || blockhash.isBlank()) {
            return false;
        }
        RecentBlockhashVO record = byHash.get(blockhash);
        return record != null && currentSlot <= record.lastValidSlot();
    }

    RecentBlockhashVO registerFinalizedBlock(String blockhash, long slot, long blockHeight, long registeredAt) {
        RecentBlockhashVO record = new RecentBlockhashVO(
                blockhash,
                slot,
                blockHeight,
                slot + blockhashWindow - 1L,
                registeredAt
        );
        add(record);
        prune(slot);
        return record;
    }

    private void add(RecentBlockhashVO record) {
        RecentBlockhashVO previous = byHash.put(record.blockhash(), record);
        if (previous != null) {
            queue.remove(previous);
        }
        queue.addLast(record);
    }

    private void prune(long currentSlot) {
        while (!queue.isEmpty()) {
            RecentBlockhashVO head = queue.peekFirst();
            if (head == null || head.lastValidSlot() >= currentSlot) {
                return;
            }
            queue.removeFirst();
            byHash.remove(head.blockhash());
        }
    }
}
