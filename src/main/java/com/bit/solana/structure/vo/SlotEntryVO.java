package com.bit.solana.structure.vo;

import java.util.ArrayList;
import java.util.List;

public class SlotEntryVO {
    private long slot;
    private int entryIndex;
    private long sequence;
    private long tickHeight;
    private String entryType;
    private String previousHash;
    private String currentHash;
    private long recordedAt;
    private int transactionCount;
    private List<String> transactionIds = new ArrayList<>();
    private List<TransactionResultVO> transactionResults = new ArrayList<>();

    public long getSlot() {
        return slot;
    }

    public void setSlot(long slot) {
        this.slot = slot;
    }

    public int getEntryIndex() {
        return entryIndex;
    }

    public void setEntryIndex(int entryIndex) {
        this.entryIndex = entryIndex;
    }

    public long getSequence() {
        return sequence;
    }

    public void setSequence(long sequence) {
        this.sequence = sequence;
    }

    public long getTickHeight() {
        return tickHeight;
    }

    public void setTickHeight(long tickHeight) {
        this.tickHeight = tickHeight;
    }

    public String getEntryType() {
        return entryType;
    }

    public void setEntryType(String entryType) {
        this.entryType = entryType;
    }

    public String getPreviousHash() {
        return previousHash;
    }

    public void setPreviousHash(String previousHash) {
        this.previousHash = previousHash;
    }

    public String getCurrentHash() {
        return currentHash;
    }

    public void setCurrentHash(String currentHash) {
        this.currentHash = currentHash;
    }

    public long getRecordedAt() {
        return recordedAt;
    }

    public void setRecordedAt(long recordedAt) {
        this.recordedAt = recordedAt;
    }

    public int getTransactionCount() {
        return transactionCount;
    }

    public void setTransactionCount(int transactionCount) {
        this.transactionCount = transactionCount;
    }

    public List<String> getTransactionIds() {
        return transactionIds;
    }

    public void setTransactionIds(List<String> transactionIds) {
        this.transactionIds = transactionIds;
    }

    public List<TransactionResultVO> getTransactionResults() {
        return transactionResults;
    }

    public void setTransactionResults(List<TransactionResultVO> transactionResults) {
        this.transactionResults = transactionResults;
    }
}
