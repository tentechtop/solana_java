package com.bit.solana.blockchain.impl;

import com.bit.solana.config.NodeProperties;
import com.bit.solana.database.rocksDb.LedgerRocksStore;
import com.bit.solana.poh.POHEngine;
import com.bit.solana.structure.dto.CreateLedgerAccountRequest;
import com.bit.solana.structure.dto.CreateLedgerAccountResponse;
import com.bit.solana.structure.dto.SubmitLedgerTransactionRequest;
import com.bit.solana.structure.dto.SubmitLedgerTransactionResponse;
import com.bit.solana.structure.vo.LedgerAccountVO;
import com.bit.solana.structure.vo.LedgerTransactionVO;
import com.bit.solana.structure.vo.RecentBlockhashVO;
import com.bit.solana.structure.vo.TransactionResultVO;
import com.bit.solana.util.LedgerCryptoSupport;
import com.bit.solana.util.LedgerHashSupport;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

public class LedgerCoordinator {
    private static final String SYSTEM_PROGRAM = "system";

    private final LedgerRocksStore store;
    private final POHEngine pohEngine;
    private final NodeProperties properties;
    private final BlockingQueue<LedgerTransactionVO> ingressQueue = new LinkedBlockingQueue<>();
    private final ConcurrentHashMap<String, ReentrantLock> accountLocks = new ConcurrentHashMap<>();

    private ExecutorService executionPool;
    private Thread bankingThread;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public LedgerCoordinator(LedgerRocksStore store, POHEngine pohEngine, NodeProperties properties) {
        this.store = store;
        this.pohEngine = pohEngine;
        this.properties = properties;
    }

    @PostConstruct
    public void start() {
        pohEngine.start();
        executionPool = Executors.newFixedThreadPool(
                Math.max(2, properties.getExecutionWorkers()),
                runnable -> {
                    Thread thread = new Thread(runnable, "solana-banking");
                    thread.setDaemon(true);
                    return thread;
                }
        );
        running.set(true);
        bankingThread = new Thread(this::bankingLoop, "solana-ingress");
        bankingThread.setDaemon(true);
        bankingThread.start();
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        if (bankingThread != null) {
            bankingThread.interrupt();
            try {
                bankingThread.join(2_000L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        if (executionPool != null) {
            executionPool.shutdownNow();
        }
    }

    public CreateLedgerAccountResponse createAccount(CreateLedgerAccountRequest request) {
        int dataSize = request == null || request.dataSize() == null ? 0 : request.dataSize();
        if (dataSize < 0) {
            throw new IllegalArgumentException("dataSize must be >= 0");
        }
        long minimumRent = properties.getRentBaseLamports()
                + (long) dataSize * properties.getRentPerByteLamports();
        long lamports = request == null || request.initialLamports() == null
                ? minimumRent
                : request.initialLamports();
        if (lamports < minimumRent) {
            throw new IllegalArgumentException("initialLamports must be >= minimum rent");
        }

        KeyPair keyPair = LedgerCryptoSupport.generateKeyPair();
        String publicKey = LedgerCryptoSupport.encodePublicKey(keyPair.getPublic());
        String privateKey = LedgerCryptoSupport.encodePrivateKey(keyPair.getPrivate());
        String accountId = LedgerCryptoSupport.accountIdFromPublicKey(publicKey);

        LedgerAccountVO account = new LedgerAccountVO();
        account.setAccountId(accountId);
        account.setOwnerProgram(SYSTEM_PROGRAM);
        account.setEncodedPublicKey(publicKey);
        account.setLamports(lamports);
        account.setDataSize(dataSize);
        account.setMinimumRent(minimumRent);
        account.setRentExempt(lamports >= minimumRent);
        account.setUpdatedAt(System.currentTimeMillis());
        account.setLastUpdatedSlot(pohEngine.getCurrentSlot());
        store.putAccount(account);

        return new CreateLedgerAccountResponse(accountId, publicKey, privateKey, minimumRent, lamports);
    }

    public SubmitLedgerTransactionResponse submitTransaction(SubmitLedgerTransactionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        if (request.fromAccountId() == null || request.fromAccountId().isBlank()) {
            throw new IllegalArgumentException("fromAccountId is required");
        }
        if (request.toAccountId() == null || request.toAccountId().isBlank()) {
            throw new IllegalArgumentException("toAccountId is required");
        }
        if (request.fromAccountId().equals(request.toAccountId())) {
            throw new IllegalArgumentException("fromAccountId and toAccountId must be different");
        }
        if (request.privateKey() == null || request.privateKey().isBlank()) {
            throw new IllegalArgumentException("privateKey is required");
        }
        if (request.lamports() <= 0) {
            throw new IllegalArgumentException("lamports must be > 0");
        }

        LedgerAccountVO sender = store.getAccount(request.fromAccountId());
        if (sender == null) {
            throw new IllegalArgumentException("Sender account not found");
        }
        LedgerAccountVO recipient = store.getAccount(request.toAccountId());
        if (recipient == null) {
            throw new IllegalArgumentException("Recipient account not found");
        }

        RecentBlockhashVO recentBlockhashInfo = pohEngine.getRecentBlockhashInfo();
        String recentBlockHash = recentBlockhashInfo == null
                ? pohEngine.getRecentBlockhash()
                : recentBlockhashInfo.blockhash();
        long submittedAt = System.currentTimeMillis();
        byte[] payload = transactionPayload(
                request.fromAccountId(),
                request.toAccountId(),
                request.lamports(),
                recentBlockHash,
                submittedAt
        );
        byte[] signatureBytes = LedgerCryptoSupport.sign(payload, request.privateKey());
        if (!LedgerCryptoSupport.verify(payload, signatureBytes, sender.getEncodedPublicKey())) {
            throw new IllegalArgumentException("Private key does not match sender account");
        }

        LedgerTransactionVO transaction = new LedgerTransactionVO();
        transaction.setFromAccountId(request.fromAccountId());
        transaction.setToAccountId(request.toAccountId());
        transaction.setLamports(request.lamports());
        transaction.setRecentBlockHash(recentBlockHash);
        transaction.setSubmittedAt(submittedAt);
        transaction.setSignature(LedgerCryptoSupport.encodeSignature(signatureBytes));
        transaction.setMessageHash(LedgerHashSupport.sha256Hex(payload));
        transaction.setTransactionId(LedgerHashSupport.sha256Hex(signatureBytes));
        transaction.setLastValidSlot(recentBlockhashInfo == null ? -1L : recentBlockhashInfo.lastValidSlot());
        transaction.setStatus("RECEIVED");

        store.putTransaction(transaction);
        ingressQueue.offer(transaction);
        return new SubmitLedgerTransactionResponse(
                transaction.getTransactionId(),
                transaction.getStatus(),
                recentBlockHash,
                transaction.getLastValidSlot()
        );
    }

    public LedgerAccountVO getAccount(String accountId) {
        return store.getAccount(accountId);
    }

    public LedgerTransactionVO getTransaction(String transactionId) {
        return store.getTransaction(transactionId);
    }

    private void bankingLoop() {
        while (running.get()) {
            try {
                List<LedgerTransactionVO> batch = drainPendingTransactions();
                if (batch.isEmpty()) {
                    continue;
                }
                processBatch(batch);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private List<LedgerTransactionVO> drainPendingTransactions() throws InterruptedException {
        List<LedgerTransactionVO> batch = new ArrayList<>();
        LedgerTransactionVO first = ingressQueue.poll(properties.getIngressPollMs(), TimeUnit.MILLISECONDS);
        if (first == null) {
            return batch;
        }
        batch.add(first);
        ingressQueue.drainTo(batch, Math.max(0, properties.getMaxTransactionsPerEntry() - 1));
        return batch;
    }

    private void processBatch(List<LedgerTransactionVO> batch) {
        List<LedgerTransactionVO> verifiedTransactions = new ArrayList<>();
        for (LedgerTransactionVO transaction : batch) {
            if (sigVerify(transaction)) {
                verifiedTransactions.add(transaction);
            }
        }

        List<List<LedgerTransactionVO>> executionWaves = buildExecutionWaves(verifiedTransactions);
        for (List<LedgerTransactionVO> wave : executionWaves) {
            List<CompletableFuture<ExecutionOutcome>> futures = wave.stream()
                    .map(transaction -> CompletableFuture.supplyAsync(() -> executeTransaction(transaction), executionPool))
                    .toList();

            List<LedgerTransactionVO> recordedTransactions = new ArrayList<>();
            List<TransactionResultVO> results = new ArrayList<>();
            for (CompletableFuture<ExecutionOutcome> future : futures) {
                ExecutionOutcome outcome = future.join();
                recordedTransactions.add(outcome.transaction());
                results.add(outcome.result());
            }

            pohEngine.recordTransactions(recordedTransactions, results);
            for (LedgerTransactionVO transaction : recordedTransactions) {
                store.putTransaction(transaction);
            }
        }
    }

    private boolean sigVerify(LedgerTransactionVO transaction) {
        LedgerAccountVO sender = store.getAccount(transaction.getFromAccountId());
        if (sender == null) {
            rejectTransaction(transaction, "Sender account not found");
            return false;
        }

        if (!pohEngine.isRecentBlockhashValid(transaction.getRecentBlockHash())) {
            rejectTransaction(transaction, "Recent blockhash expired");
            return false;
        }

        byte[] payload = transactionPayload(
                transaction.getFromAccountId(),
                transaction.getToAccountId(),
                transaction.getLamports(),
                transaction.getRecentBlockHash(),
                transaction.getSubmittedAt()
        );
        boolean verified = LedgerCryptoSupport.verify(payload, transaction.getSignature(), sender.getEncodedPublicKey());
        if (!verified) {
            rejectTransaction(transaction, "Signature verification failed");
            return false;
        }

        transaction.setStatus("SIGVERIFIED");
        store.putTransaction(transaction);
        return true;
    }

    private ExecutionOutcome executeTransaction(LedgerTransactionVO transaction) {
        List<ReentrantLock> locks = lockAccounts(transaction.getFromAccountId(), transaction.getToAccountId());
        try {
            LedgerAccountVO sender = store.getAccount(transaction.getFromAccountId());
            LedgerAccountVO recipient = store.getAccount(transaction.getToAccountId());
            if (sender == null || recipient == null) {
                return failExecution(transaction, "Account missing during execution");
            }

            long remaining = sender.getLamports() - transaction.getLamports();
            if (remaining < 0) {
                return failExecution(transaction, "Insufficient lamports");
            }
            if (remaining != 0 && remaining < sender.getMinimumRent()) {
                return failExecution(transaction, "Sender would fall below minimum rent");
            }

            long now = System.currentTimeMillis();
            long currentSlot = pohEngine.getCurrentSlot();
            sender.setLamports(remaining);
            sender.setRentExempt(remaining == 0 || remaining >= sender.getMinimumRent());
            sender.setUpdatedAt(now);
            sender.setLastUpdatedSlot(currentSlot);

            recipient.setLamports(recipient.getLamports() + transaction.getLamports());
            recipient.setRentExempt(recipient.getLamports() >= recipient.getMinimumRent());
            recipient.setUpdatedAt(now);
            recipient.setLastUpdatedSlot(currentSlot);

            transaction.setStatus("EXECUTED");
            transaction.setFailureReason(null);
            transaction.setProcessedAt(now);
            store.persistExecution(transaction, List.of(sender, recipient));

            TransactionResultVO result = new TransactionResultVO();
            result.setTransactionId(transaction.getTransactionId());
            result.setSuccess(true);
            result.setMessage("ok");
            return new ExecutionOutcome(transaction, result);
        } finally {
            unlockAccounts(locks);
        }
    }

    private ExecutionOutcome failExecution(LedgerTransactionVO transaction, String message) {
        transaction.setStatus("FAILED");
        transaction.setFailureReason(message);
        transaction.setProcessedAt(System.currentTimeMillis());
        store.putTransaction(transaction);

        TransactionResultVO result = new TransactionResultVO();
        result.setTransactionId(transaction.getTransactionId());
        result.setSuccess(false);
        result.setMessage(message);
        return new ExecutionOutcome(transaction, result);
    }

    private void rejectTransaction(LedgerTransactionVO transaction, String reason) {
        transaction.setStatus("REJECTED");
        transaction.setFailureReason(reason);
        transaction.setProcessedAt(System.currentTimeMillis());
        store.putTransaction(transaction);
    }

    private List<List<LedgerTransactionVO>> buildExecutionWaves(List<LedgerTransactionVO> transactions) {
        List<LedgerTransactionVO> sorted = new ArrayList<>(transactions);
        sorted.sort(Comparator.comparingLong(LedgerTransactionVO::getLamports).reversed());

        List<List<LedgerTransactionVO>> waves = new ArrayList<>();
        List<Set<String>> touchedAccounts = new ArrayList<>();
        for (LedgerTransactionVO transaction : sorted) {
            Set<String> writable = writableAccounts(transaction);
            boolean placed = false;
            for (int index = 0; index < waves.size(); index++) {
                if (disjoint(writable, touchedAccounts.get(index))) {
                    waves.get(index).add(transaction);
                    touchedAccounts.get(index).addAll(writable);
                    placed = true;
                    break;
                }
            }
            if (!placed) {
                List<LedgerTransactionVO> newWave = new ArrayList<>();
                newWave.add(transaction);
                waves.add(newWave);
                touchedAccounts.add(new HashSet<>(writable));
            }
        }
        return waves;
    }

    private Set<String> writableAccounts(LedgerTransactionVO transaction) {
        Set<String> writable = new HashSet<>();
        writable.add(transaction.getFromAccountId());
        writable.add(transaction.getToAccountId());
        return writable;
    }

    private boolean disjoint(Set<String> left, Set<String> right) {
        for (String key : left) {
            if (right.contains(key)) {
                return false;
            }
        }
        return true;
    }

    private List<ReentrantLock> lockAccounts(String firstAccountId, String secondAccountId) {
        List<String> accountIds = new ArrayList<>(Set.of(firstAccountId, secondAccountId));
        accountIds.sort(String::compareTo);
        List<ReentrantLock> locks = new ArrayList<>(accountIds.size());
        for (String accountId : accountIds) {
            ReentrantLock lock = accountLocks.computeIfAbsent(accountId, ignored -> new ReentrantLock());
            lock.lock();
            locks.add(lock);
        }
        return locks;
    }

    private void unlockAccounts(List<ReentrantLock> locks) {
        for (int index = locks.size() - 1; index >= 0; index--) {
            locks.get(index).unlock();
        }
    }

    private byte[] transactionPayload(
            String fromAccountId,
            String toAccountId,
            long lamports,
            String recentBlockHash,
            long submittedAt
    ) {
        String payload = fromAccountId
                + "|"
                + toAccountId
                + "|"
                + lamports
                + "|"
                + recentBlockHash
                + "|"
                + submittedAt;
        return payload.getBytes(StandardCharsets.UTF_8);
    }

    private record ExecutionOutcome(LedgerTransactionVO transaction, TransactionResultVO result) {
    }
}
