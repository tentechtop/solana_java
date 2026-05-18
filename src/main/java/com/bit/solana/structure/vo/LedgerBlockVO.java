package com.bit.solana.structure.vo;

import java.util.ArrayList;
import java.util.List;

public class LedgerBlockVO {
    private String blockId;
    private long slot;
    private long height;
    private String previousBlockHash;
    private long parentSlot;
    private String pohStartHash;
    private String pohEndHash;
    private String stateHash;
    private String leaderIdentity;
    private long createdAt;
    private int entryCount;
    private int transactionCount;
    private boolean emptyBlock;
    private List<String> transactionIds = new ArrayList<>();
    private List<TransactionResultVO> transactionResults = new ArrayList<>();
    private List<SlotEntryVO> entries = new ArrayList<>();

    public String getBlockId() {
        return blockId;
    }

    public void setBlockId(String blockId) {
        this.blockId = blockId;
    }

    public long getSlot() {
        return slot;
    }

    public void setSlot(long slot) {
        this.slot = slot;
    }

    public long getHeight() {
        return height;
    }

    public void setHeight(long height) {
        this.height = height;
    }

    public String getPreviousBlockHash() {
        return previousBlockHash;
    }

    public void setPreviousBlockHash(String previousBlockHash) {
        this.previousBlockHash = previousBlockHash;
    }

    public long getParentSlot() {
        return parentSlot;
    }

    public void setParentSlot(long parentSlot) {
        this.parentSlot = parentSlot;
    }

    public String getPohStartHash() {
        return pohStartHash;
    }

    public void setPohStartHash(String pohStartHash) {
        this.pohStartHash = pohStartHash;
    }

    public String getPohEndHash() {
        return pohEndHash;
    }

    public void setPohEndHash(String pohEndHash) {
        this.pohEndHash = pohEndHash;
    }

    public String getStateHash() {
        return stateHash;
    }

    public void setStateHash(String stateHash) {
        this.stateHash = stateHash;
    }

    public String getLeaderIdentity() {
        return leaderIdentity;
    }

    public void setLeaderIdentity(String leaderIdentity) {
        this.leaderIdentity = leaderIdentity;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public int getEntryCount() {
        return entryCount;
    }

    public void setEntryCount(int entryCount) {
        this.entryCount = entryCount;
    }

    public int getTransactionCount() {
        return transactionCount;
    }

    public void setTransactionCount(int transactionCount) {
        this.transactionCount = transactionCount;
    }

    public boolean isEmptyBlock() {
        return emptyBlock;
    }

    public void setEmptyBlock(boolean emptyBlock) {
        this.emptyBlock = emptyBlock;
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

    public List<SlotEntryVO> getEntries() {
        return entries;
    }

    public void setEntries(List<SlotEntryVO> entries) {
        this.entries = entries;
    }
}
