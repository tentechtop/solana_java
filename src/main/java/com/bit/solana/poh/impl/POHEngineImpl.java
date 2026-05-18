package com.bit.solana.poh.impl;

import com.bit.solana.config.NodeProperties;
import com.bit.solana.database.rocksDb.LedgerRocksStore;
import com.bit.solana.poh.POHEngine;
import com.bit.solana.structure.vo.LedgerBlockVO;
import com.bit.solana.structure.vo.LedgerTransactionVO;
import com.bit.solana.structure.vo.LeaderSlotVO;
import com.bit.solana.structure.vo.PohRecordVO;
import com.bit.solana.structure.vo.PohStatusVO;
import com.bit.solana.structure.vo.RecentBlockhashVO;
import com.bit.solana.structure.vo.SlotEntryVO;
import com.bit.solana.structure.vo.TransactionResultVO;
import com.bit.solana.util.LedgerHashSupport;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class POHEngineImpl implements POHEngine {
    private final NodeProperties properties;
    private final LedgerRocksStore store;
    private final RecentBlockhashQueue recentBlockhashQueue;
    private final LeaderSchedule leaderSchedule;

    private final Object slotLock = new Object();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final List<SlotEntryVO> currentEntries = new ArrayList<>();

    private Thread tickThread;
    private long currentSlot;
    private long currentTickInSlot;
    private long globalSequence;
    private int currentEntryIndex;
    private long latestBlockHeight;
    private long parentSlot;
    private String parentBlockHash;
    private String currentHash;
    private String slotStartHash;

    public POHEngineImpl(NodeProperties properties, LedgerRocksStore store) {
        this.properties = properties;
        this.store = store;
        this.recentBlockhashQueue = new RecentBlockhashQueue(properties.getRecentBlockhashWindow());
        this.leaderSchedule = new LeaderSchedule(properties);
    }

    @PostConstruct
    public void initialize() {
        restoreState();
        start();
    }

    @PreDestroy
    public void shutdown() {
        stop();
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        tickThread = new Thread(this::tickLoop, "solana-poh");
        tickThread.setDaemon(true);
        tickThread.start();
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        if (tickThread != null) {
            tickThread.interrupt();
            try {
                tickThread.join(2_000L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public long getCurrentSlot() {
        synchronized (slotLock) {
            return currentSlot;
        }
    }

    @Override
    public String getRecentBlockhash() {
        synchronized (slotLock) {
            RecentBlockhashVO recentBlockhash = recentBlockhashQueue.latest();
            return recentBlockhash == null ? store.getGenesisHash() : recentBlockhash.blockhash();
        }
    }

    @Override
    public RecentBlockhashVO getRecentBlockhashInfo() {
        synchronized (slotLock) {
            return recentBlockhashQueue.latest();
        }
    }

    @Override
    public boolean isRecentBlockhashValid(String recentBlockhash) {
        synchronized (slotLock) {
            return recentBlockhashQueue.isValid(recentBlockhash, currentSlot);
        }
    }

    @Override
    public LeaderSlotVO getLeaderSlot() {
        synchronized (slotLock) {
            return leaderSchedule.slotLeader(currentSlot);
        }
    }

    @Override
    public void recordTransactions(List<LedgerTransactionVO> transactions, List<TransactionResultVO> results) {
        if (transactions == null || transactions.isEmpty()) {
            return;
        }
        synchronized (slotLock) {
            String previousHash = currentHash;
            String payload = transactions.stream()
                    .map(transaction -> transaction.getTransactionId() + ":" + transaction.getStatus())
                    .reduce("", (left, right) -> left + "|" + right);
            currentHash = LedgerHashSupport.mix(
                    previousHash,
                    "entry",
                    Long.toString(currentSlot),
                    Integer.toString(currentEntryIndex),
                    payload
            );
            globalSequence++;

            long recordedAt = System.currentTimeMillis();
            SlotEntryVO entry = new SlotEntryVO();
            entry.setSlot(currentSlot);
            entry.setEntryIndex(currentEntryIndex);
            entry.setSequence(globalSequence);
            entry.setTickHeight(currentTickInSlot);
            entry.setEntryType("TRANSACTION");
            entry.setPreviousHash(previousHash);
            entry.setCurrentHash(currentHash);
            entry.setRecordedAt(recordedAt);
            entry.setTransactionCount(transactions.size());
            entry.setTransactionIds(transactions.stream().map(LedgerTransactionVO::getTransactionId).toList());
            entry.setTransactionResults(new ArrayList<>(results));
            currentEntries.add(entry);

            for (LedgerTransactionVO transaction : transactions) {
                PohRecordVO record = new PohRecordVO();
                record.setSequence(globalSequence);
                record.setSlot(currentSlot);
                record.setTickHeight(currentTickInSlot);
                record.setEntryIndex(currentEntryIndex);
                record.setEntryType("TRANSACTION");
                record.setPreviousHash(previousHash);
                record.setCurrentHash(currentHash);
                record.setRecordedAt(recordedAt);
                transaction.setSlot(currentSlot);
                transaction.setEntryIndex(currentEntryIndex);
                transaction.setPohRecord(record);
            }

            currentEntryIndex++;
        }
    }

    @Override
    public PohStatusVO getStatus() {
        synchronized (slotLock) {
            return new PohStatusVO(
                    currentSlot,
                    currentTickInSlot,
                    latestBlockHeight,
                    currentHash,
                    recentBlockhashQueue.latest(),
                    leaderSchedule.slotLeader(currentSlot),
                    properties.getTicksPerSlot()
            );
        }
    }

    private void restoreState() {
        synchronized (slotLock) {
            LedgerBlockVO latestBlock = store.getLatestBlock();
            recentBlockhashQueue.bootstrap(store.getGenesisHash(), List.of());
            if (latestBlock == null) {
                currentSlot = 0L;
                currentTickInSlot = 0L;
                globalSequence = 0L;
                currentEntryIndex = 0;
                latestBlockHeight = 0L;
                parentSlot = -1L;
                parentBlockHash = "GENESIS";
                currentHash = store.getGenesisHash();
                slotStartHash = currentHash;
                currentEntries.clear();
                return;
            }

            currentSlot = latestBlock.getSlot() + 1L;
            currentTickInSlot = 0L;
            currentEntryIndex = 0;
            latestBlockHeight = latestBlock.getHeight();
            parentSlot = latestBlock.getSlot();
            parentBlockHash = latestBlock.getBlockId();
            currentHash = latestBlock.getPohEndHash();
            slotStartHash = currentHash;
            currentEntries.clear();
            List<SlotEntryVO> entries = latestBlock.getEntries();
            if (entries != null && !entries.isEmpty()) {
                globalSequence = entries.get(entries.size() - 1).getSequence();
            }
            recentBlockhashQueue.bootstrap(
                    store.getGenesisHash(),
                    store.loadRecentBlockhashes(
                            properties.getRecentBlockhashWindow(),
                            properties.getRecentBlockhashWindow()
                    )
            );
        }
    }

    private void tickLoop() {
        while (running.get()) {
            try {
                Thread.sleep(properties.getTickIntervalMs());
                generateTick();
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void generateTick() {
        synchronized (slotLock) {
            String previousHash = currentHash;
            currentHash = LedgerHashSupport.mix(
                    previousHash,
                    "tick",
                    Long.toString(currentSlot),
                    Long.toString(currentTickInSlot + 1L),
                    Long.toString(globalSequence + 1L)
            );
            globalSequence++;

            SlotEntryVO entry = new SlotEntryVO();
            entry.setSlot(currentSlot);
            entry.setEntryIndex(currentEntryIndex);
            entry.setSequence(globalSequence);
            entry.setTickHeight(currentTickInSlot + 1L);
            entry.setEntryType("TICK");
            entry.setPreviousHash(previousHash);
            entry.setCurrentHash(currentHash);
            entry.setRecordedAt(System.currentTimeMillis());
            entry.setTransactionCount(0);
            currentEntries.add(entry);

            currentTickInSlot++;
            currentEntryIndex++;

            if (currentTickInSlot >= properties.getTicksPerSlot()) {
                sealCurrentSlot();
            }
        }
    }

    private void sealCurrentSlot() {
        LeaderSlotVO leaderSlot = leaderSchedule.slotLeader(currentSlot);
        LedgerBlockVO block = new LedgerBlockVO();
        block.setSlot(currentSlot);
        block.setHeight(latestBlockHeight + 1L);
        block.setPreviousBlockHash(parentBlockHash);
        block.setParentSlot(parentSlot);
        block.setPohStartHash(slotStartHash);
        block.setPohEndHash(currentHash);
        block.setBlockId(currentHash);
        block.setLeaderIdentity(leaderSlot.slotLeader());
        block.setCreatedAt(System.currentTimeMillis());
        block.setEntryCount(currentEntries.size());
        block.setEntries(new ArrayList<>(currentEntries));

        List<String> transactionIds = new ArrayList<>();
        List<TransactionResultVO> transactionResults = new ArrayList<>();
        for (SlotEntryVO entry : currentEntries) {
            if (entry.getTransactionIds() != null) {
                transactionIds.addAll(entry.getTransactionIds());
            }
            if (entry.getTransactionResults() != null) {
                transactionResults.addAll(entry.getTransactionResults());
            }
        }

        block.setTransactionIds(transactionIds);
        block.setTransactionResults(transactionResults);
        block.setTransactionCount(transactionIds.size());
        block.setEmptyBlock(transactionIds.isEmpty());
        block.setStateHash(store.computeStateHash());

        store.persistBlock(block);
        store.attachBlockToTransactions(block);

        recentBlockhashQueue.registerFinalizedBlock(
                block.getBlockId(),
                block.getSlot(),
                block.getHeight(),
                block.getCreatedAt()
        );

        latestBlockHeight = block.getHeight();
        parentSlot = currentSlot;
        parentBlockHash = block.getBlockId();
        currentSlot++;
        currentTickInSlot = 0L;
        currentEntryIndex = 0;
        slotStartHash = currentHash;
        currentEntries.clear();

        store.putMeta("current-slot", Long.toString(currentSlot));
        store.putMeta("current-sequence", Long.toString(globalSequence));
        store.putMeta("current-hash", currentHash);
    }
}
