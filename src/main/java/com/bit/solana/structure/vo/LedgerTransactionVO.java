package com.bit.solana.structure.vo;

public class LedgerTransactionVO {
    private String transactionId;
    private String fromAccountId;
    private String toAccountId;
    private long lamports;
    private String recentBlockHash;
    private String signature;
    private String messageHash;
    private String status;
    private long submittedAt;
    private long processedAt;
    private String failureReason;
    private String blockId;
    private long blockHeight;
    private long lastValidSlot = -1;
    private long slot = -1;
    private int entryIndex = -1;
    private PohRecordVO pohRecord;

    public String getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(String transactionId) {
        this.transactionId = transactionId;
    }

    public String getFromAccountId() {
        return fromAccountId;
    }

    public void setFromAccountId(String fromAccountId) {
        this.fromAccountId = fromAccountId;
    }

    public String getToAccountId() {
        return toAccountId;
    }

    public void setToAccountId(String toAccountId) {
        this.toAccountId = toAccountId;
    }

    public long getLamports() {
        return lamports;
    }

    public void setLamports(long lamports) {
        this.lamports = lamports;
    }

    public String getRecentBlockHash() {
        return recentBlockHash;
    }

    public void setRecentBlockHash(String recentBlockHash) {
        this.recentBlockHash = recentBlockHash;
    }

    public String getSignature() {
        return signature;
    }

    public void setSignature(String signature) {
        this.signature = signature;
    }

    public String getMessageHash() {
        return messageHash;
    }

    public void setMessageHash(String messageHash) {
        this.messageHash = messageHash;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public long getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(long submittedAt) {
        this.submittedAt = submittedAt;
    }

    public long getProcessedAt() {
        return processedAt;
    }

    public void setProcessedAt(long processedAt) {
        this.processedAt = processedAt;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public String getBlockId() {
        return blockId;
    }

    public void setBlockId(String blockId) {
        this.blockId = blockId;
    }

    public long getBlockHeight() {
        return blockHeight;
    }

    public void setBlockHeight(long blockHeight) {
        this.blockHeight = blockHeight;
    }

    public long getLastValidSlot() {
        return lastValidSlot;
    }

    public void setLastValidSlot(long lastValidSlot) {
        this.lastValidSlot = lastValidSlot;
    }

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

    public PohRecordVO getPohRecord() {
        return pohRecord;
    }

    public void setPohRecord(PohRecordVO pohRecord) {
        this.pohRecord = pohRecord;
    }
}
