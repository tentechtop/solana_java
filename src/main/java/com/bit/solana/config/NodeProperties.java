package com.bit.solana.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "node")
public class NodeProperties {
    private String storagePath = "./data/solana-node";
    private String validatorIdentity = "validator-local";
    private long tickIntervalMs = 100L;
    private int ticksPerSlot = 8;
    private int maxTransactionsPerEntry = 128;
    private int ingressPollMs = 25;
    private int executionWorkers = 4;
    private long rentBaseLamports = 1_000L;
    private long rentPerByteLamports = 10L;
    private int recentBlockhashWindow = 150;

    public String getStoragePath() {
        return storagePath;
    }

    public void setStoragePath(String storagePath) {
        this.storagePath = storagePath;
    }

    public String getValidatorIdentity() {
        return validatorIdentity;
    }

    public void setValidatorIdentity(String validatorIdentity) {
        this.validatorIdentity = validatorIdentity;
    }

    public long getTickIntervalMs() {
        return tickIntervalMs;
    }

    public void setTickIntervalMs(long tickIntervalMs) {
        this.tickIntervalMs = tickIntervalMs;
    }

    public int getTicksPerSlot() {
        return ticksPerSlot;
    }

    public void setTicksPerSlot(int ticksPerSlot) {
        this.ticksPerSlot = ticksPerSlot;
    }

    public int getMaxTransactionsPerEntry() {
        return maxTransactionsPerEntry;
    }

    public void setMaxTransactionsPerEntry(int maxTransactionsPerEntry) {
        this.maxTransactionsPerEntry = maxTransactionsPerEntry;
    }

    public int getIngressPollMs() {
        return ingressPollMs;
    }

    public void setIngressPollMs(int ingressPollMs) {
        this.ingressPollMs = ingressPollMs;
    }

    public int getExecutionWorkers() {
        return executionWorkers;
    }

    public void setExecutionWorkers(int executionWorkers) {
        this.executionWorkers = executionWorkers;
    }

    public long getRentBaseLamports() {
        return rentBaseLamports;
    }

    public void setRentBaseLamports(long rentBaseLamports) {
        this.rentBaseLamports = rentBaseLamports;
    }

    public long getRentPerByteLamports() {
        return rentPerByteLamports;
    }

    public void setRentPerByteLamports(long rentPerByteLamports) {
        this.rentPerByteLamports = rentPerByteLamports;
    }

    public int getRecentBlockhashWindow() {
        return recentBlockhashWindow;
    }

    public void setRecentBlockhashWindow(int recentBlockhashWindow) {
        this.recentBlockhashWindow = recentBlockhashWindow;
    }
}
