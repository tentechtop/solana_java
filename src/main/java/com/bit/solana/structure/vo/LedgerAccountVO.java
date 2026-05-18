package com.bit.solana.structure.vo;

public class LedgerAccountVO {
    private String accountId;
    private String ownerProgram;
    private String encodedPublicKey;
    private long lamports;
    private int dataSize;
    private long minimumRent;
    private boolean rentExempt;
    private long updatedAt;
    private long lastUpdatedSlot;

    public LedgerAccountVO copy() {
        LedgerAccountVO account = new LedgerAccountVO();
        account.setAccountId(accountId);
        account.setOwnerProgram(ownerProgram);
        account.setEncodedPublicKey(encodedPublicKey);
        account.setLamports(lamports);
        account.setDataSize(dataSize);
        account.setMinimumRent(minimumRent);
        account.setRentExempt(rentExempt);
        account.setUpdatedAt(updatedAt);
        account.setLastUpdatedSlot(lastUpdatedSlot);
        return account;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public String getOwnerProgram() {
        return ownerProgram;
    }

    public void setOwnerProgram(String ownerProgram) {
        this.ownerProgram = ownerProgram;
    }

    public String getEncodedPublicKey() {
        return encodedPublicKey;
    }

    public void setEncodedPublicKey(String encodedPublicKey) {
        this.encodedPublicKey = encodedPublicKey;
    }

    public long getLamports() {
        return lamports;
    }

    public void setLamports(long lamports) {
        this.lamports = lamports;
    }

    public int getDataSize() {
        return dataSize;
    }

    public void setDataSize(int dataSize) {
        this.dataSize = dataSize;
    }

    public long getMinimumRent() {
        return minimumRent;
    }

    public void setMinimumRent(long minimumRent) {
        this.minimumRent = minimumRent;
    }

    public boolean isRentExempt() {
        return rentExempt;
    }

    public void setRentExempt(boolean rentExempt) {
        this.rentExempt = rentExempt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }

    public long getLastUpdatedSlot() {
        return lastUpdatedSlot;
    }

    public void setLastUpdatedSlot(long lastUpdatedSlot) {
        this.lastUpdatedSlot = lastUpdatedSlot;
    }
}
