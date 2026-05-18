package com.bit.solana.database.rocksDb;

import com.bit.solana.config.NodeProperties;
import com.bit.solana.structure.vo.LedgerAccountVO;
import com.bit.solana.structure.vo.LedgerBlockVO;
import com.bit.solana.structure.vo.LedgerTransactionVO;
import com.bit.solana.structure.vo.RecentBlockhashVO;
import com.bit.solana.structure.vo.SlotEntryVO;
import com.bit.solana.structure.vo.SlotMetaVO;
import com.bit.solana.util.LedgerHashSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class LedgerRocksStore {
    private static final String GENESIS_HASH = "genesis-hash";

    static {
        RocksDB.loadLibrary();
    }

    private final NodeProperties properties;
    private final ObjectMapper objectMapper;

    private RocksDB database;
    private Options options;
    private WriteOptions writeOptions;

    public LedgerRocksStore(NodeProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public synchronized void open() {
        try {
            Path storagePath = Path.of(properties.getStoragePath()).toAbsolutePath();
            Files.createDirectories(storagePath);
            options = new Options().setCreateIfMissing(true);
            writeOptions = new WriteOptions().setSync(true);
            database = RocksDB.open(options, storagePath.toString());
            if (getMeta(GENESIS_HASH) == null) {
                putMeta(GENESIS_HASH, LedgerHashSupport.mix("solana-runtime", storagePath.toString()));
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to open RocksDB", exception);
        }
    }

    @PreDestroy
    public synchronized void close() {
        if (writeOptions != null) {
            writeOptions.close();
            writeOptions = null;
        }
        if (database != null) {
            database.close();
            database = null;
        }
        if (options != null) {
            options.close();
            options = null;
        }
    }

    public synchronized String getGenesisHash() {
        return getMeta(GENESIS_HASH);
    }

    public synchronized LedgerAccountVO getAccount(String accountId) {
        return getJson(accountKey(accountId), LedgerAccountVO.class);
    }

    public synchronized void putAccount(LedgerAccountVO account) {
        putJson(accountKey(account.getAccountId()), account);
    }

    public synchronized LedgerTransactionVO getTransaction(String transactionId) {
        return getJson(transactionKey(transactionId), LedgerTransactionVO.class);
    }

    public synchronized void putTransaction(LedgerTransactionVO transaction) {
        putJson(transactionKey(transaction.getTransactionId()), transaction);
    }

    public synchronized void persistExecution(LedgerTransactionVO transaction, Collection<LedgerAccountVO> accounts) {
        try (WriteBatch batch = new WriteBatch()) {
            for (LedgerAccountVO account : accounts) {
                batch.put(bytes(accountKey(account.getAccountId())), objectMapper.writeValueAsBytes(account));
            }
            batch.put(bytes(transactionKey(transaction.getTransactionId())), objectMapper.writeValueAsBytes(transaction));
            database.write(writeOptions, batch);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to persist execution result", exception);
        }
    }

    public synchronized LedgerBlockVO getBlock(String blockId) {
        String slotValue = getString(blockSlotIndexKey(blockId));
        if (slotValue != null) {
            return getBlockBySlot(parseLong(slotValue, -1L));
        }
        return getJson(blockKey(blockId), LedgerBlockVO.class);
    }

    public synchronized LedgerBlockVO getBlockByHeight(long height) {
        String slotValue = getString(heightSlotIndexKey(height));
        if (slotValue != null) {
            return getBlockBySlot(parseLong(slotValue, -1L));
        }

        String blockId = getString(legacyHeightIndexKey(height));
        return blockId == null ? null : getBlock(blockId);
    }

    public synchronized LedgerBlockVO getBlockBySlot(long slot) {
        SlotMetaVO slotMeta = getJson(slotMetaKey(slot), SlotMetaVO.class);
        if (slotMeta != null) {
            return reconstructBlock(slotMeta);
        }

        String blockId = getString(legacySlotIndexKey(slot));
        return blockId == null ? null : getJson(blockKey(blockId), LedgerBlockVO.class);
    }

    public synchronized LedgerBlockVO getLatestBlock() {
        String latestSlot = getMeta("latest-slot");
        if (latestSlot != null) {
            return getBlockBySlot(parseLong(latestSlot, -1L));
        }

        String latestBlockId = getMeta("latest-block-id");
        return latestBlockId == null ? null : getBlock(latestBlockId);
    }

    public synchronized void persistBlock(LedgerBlockVO block) {
        try (WriteBatch batch = new WriteBatch()) {
            SlotMetaVO slotMeta = toSlotMeta(block);
            batch.put(bytes(slotMetaKey(block.getSlot())), objectMapper.writeValueAsBytes(slotMeta));
            for (SlotEntryVO entry : block.getEntries()) {
                batch.put(bytes(slotEntryKey(block.getSlot(), entry.getEntryIndex())), objectMapper.writeValueAsBytes(entry));
            }
            batch.put(bytes(blockSlotIndexKey(block.getBlockId())), bytes(Long.toString(block.getSlot())));
            batch.put(bytes(heightSlotIndexKey(block.getHeight())), bytes(Long.toString(block.getSlot())));
            batch.put(bytes(blockKey(block.getBlockId())), objectMapper.writeValueAsBytes(block));
            batch.put(bytes(metaKey("latest-block-id")), bytes(block.getBlockId()));
            batch.put(bytes(metaKey("latest-block-height")), bytes(Long.toString(block.getHeight())));
            batch.put(bytes(metaKey("latest-slot")), bytes(Long.toString(block.getSlot())));
            database.write(writeOptions, batch);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to persist block", exception);
        }
    }

    public synchronized void attachBlockToTransactions(LedgerBlockVO block) {
        if (block.getTransactionIds() == null || block.getTransactionIds().isEmpty()) {
            return;
        }
        try (WriteBatch batch = new WriteBatch()) {
            for (String transactionId : block.getTransactionIds()) {
                LedgerTransactionVO transaction = getTransaction(transactionId);
                if (transaction == null) {
                    continue;
                }
                transaction.setBlockId(block.getBlockId());
                transaction.setBlockHeight(block.getHeight());
                transaction.setSlot(block.getSlot());
                if ("EXECUTED".equals(transaction.getStatus())) {
                    transaction.setStatus("FINALIZED");
                }
                batch.put(bytes(transactionKey(transactionId)), objectMapper.writeValueAsBytes(transaction));
                batch.put(bytes(transactionSlotKey(transactionId)), bytes(Long.toString(block.getSlot())));
                batch.put(bytes(transactionBlockKey(transactionId)), bytes(block.getBlockId()));
            }
            database.write(writeOptions, batch);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to link transactions to block", exception);
        }
    }

    public synchronized void putMeta(String key, String value) {
        putString(metaKey(key), value);
    }

    public synchronized String getMeta(String key) {
        return getString(metaKey(key));
    }

    public synchronized String computeStateHash() {
        List<String> snapshots = new ArrayList<>();
        try (RocksIterator iterator = database.newIterator()) {
            byte[] prefix = bytes("acct:");
            iterator.seek(prefix);
            while (iterator.isValid()) {
                String key = new String(iterator.key(), StandardCharsets.UTF_8);
                if (!key.startsWith("acct:")) {
                    break;
                }
                LedgerAccountVO account = objectMapper.readValue(iterator.value(), LedgerAccountVO.class);
                snapshots.add(account.getAccountId()
                        + ":"
                        + account.getLamports()
                        + ":"
                        + account.getMinimumRent()
                        + ":"
                        + account.getLastUpdatedSlot());
                iterator.next();
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to compute state hash", exception);
        }
        return LedgerHashSupport.mix(snapshots.toArray(String[]::new));
    }

    public synchronized List<RecentBlockhashVO> loadRecentBlockhashes(int limit, int blockhashWindow) {
        List<RecentBlockhashVO> recentBlockhashes = new ArrayList<>();
        LedgerBlockVO cursor = getLatestBlock();
        while (cursor != null && recentBlockhashes.size() < limit) {
            recentBlockhashes.add(0, new RecentBlockhashVO(
                    cursor.getBlockId(),
                    cursor.getSlot(),
                    cursor.getHeight(),
                    cursor.getSlot() + Math.max(0, blockhashWindow - 1),
                    cursor.getCreatedAt()
            ));
            if (cursor.getParentSlot() < 0) {
                break;
            }
            cursor = getBlockBySlot(cursor.getParentSlot());
        }
        return recentBlockhashes;
    }

    private LedgerBlockVO reconstructBlock(SlotMetaVO slotMeta) {
        LedgerBlockVO block = new LedgerBlockVO();
        block.setBlockId(slotMeta.getBlockId());
        block.setSlot(slotMeta.getSlot());
        block.setHeight(slotMeta.getHeight());
        block.setPreviousBlockHash(slotMeta.getPreviousBlockHash());
        block.setParentSlot(slotMeta.getParentSlot());
        block.setPohStartHash(slotMeta.getPohStartHash());
        block.setPohEndHash(slotMeta.getPohEndHash());
        block.setStateHash(slotMeta.getStateHash());
        block.setLeaderIdentity(slotMeta.getLeaderIdentity());
        block.setCreatedAt(slotMeta.getCreatedAt());
        block.setEntryCount(slotMeta.getEntryCount());
        block.setTransactionCount(slotMeta.getTransactionCount());
        block.setEmptyBlock(slotMeta.isEmptyBlock());

        List<SlotEntryVO> entries = new ArrayList<>();
        List<String> transactionIds = new ArrayList<>();
        List<com.bit.solana.structure.vo.TransactionResultVO> transactionResults = new ArrayList<>();
        for (int entryIndex = 0; entryIndex < slotMeta.getEntryCount(); entryIndex++) {
            SlotEntryVO entry = getJson(slotEntryKey(slotMeta.getSlot(), entryIndex), SlotEntryVO.class);
            if (entry == null) {
                continue;
            }
            entries.add(entry);
            if (entry.getTransactionIds() != null) {
                transactionIds.addAll(entry.getTransactionIds());
            }
            if (entry.getTransactionResults() != null) {
                transactionResults.addAll(entry.getTransactionResults());
            }
        }

        block.setEntries(entries);
        block.setTransactionIds(transactionIds);
        block.setTransactionResults(transactionResults);
        return block;
    }

    private SlotMetaVO toSlotMeta(LedgerBlockVO block) {
        SlotMetaVO slotMeta = new SlotMetaVO();
        slotMeta.setBlockId(block.getBlockId());
        slotMeta.setSlot(block.getSlot());
        slotMeta.setHeight(block.getHeight());
        slotMeta.setPreviousBlockHash(block.getPreviousBlockHash());
        slotMeta.setParentSlot(block.getParentSlot());
        slotMeta.setPohStartHash(block.getPohStartHash());
        slotMeta.setPohEndHash(block.getPohEndHash());
        slotMeta.setStateHash(block.getStateHash());
        slotMeta.setLeaderIdentity(block.getLeaderIdentity());
        slotMeta.setCreatedAt(block.getCreatedAt());
        slotMeta.setEntryCount(block.getEntries() == null ? 0 : block.getEntries().size());
        slotMeta.setTransactionCount(block.getTransactionCount());
        slotMeta.setEmptyBlock(block.isEmptyBlock());
        return slotMeta;
    }

    private <T> T getJson(String key, Class<T> type) {
        try {
            byte[] value = database.get(bytes(key));
            if (value == null) {
                return null;
            }
            return objectMapper.readValue(value, type);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to load key " + key, exception);
        }
    }

    private void putJson(String key, Object value) {
        try {
            database.put(writeOptions, bytes(key), objectMapper.writeValueAsBytes(value));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to save key " + key, exception);
        }
    }

    private String getString(String key) {
        try {
            byte[] value = database.get(bytes(key));
            return value == null ? null : new String(value, StandardCharsets.UTF_8);
        } catch (RocksDBException exception) {
            throw new IllegalStateException("Unable to read key " + key, exception);
        }
    }

    private void putString(String key, String value) {
        try {
            database.put(writeOptions, bytes(key), bytes(value));
        } catch (RocksDBException exception) {
            throw new IllegalStateException("Unable to write key " + key, exception);
        }
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private String accountKey(String accountId) {
        return "acct:" + accountId;
    }

    private String transactionKey(String transactionId) {
        return "tx:" + transactionId;
    }

    private String blockKey(String blockId) {
        return "block:" + blockId;
    }

    private String legacyHeightIndexKey(long height) {
        return "height:" + String.format("%020d", height);
    }

    private String legacySlotIndexKey(long slot) {
        return "slot:" + String.format("%020d", slot);
    }

    private String slotMetaKey(long slot) {
        return "slot-meta:" + String.format("%020d", slot);
    }

    private String slotEntryKey(long slot, int entryIndex) {
        return "slot-entry:" + String.format("%020d", slot) + ":" + String.format("%06d", entryIndex);
    }

    private String blockSlotIndexKey(String blockId) {
        return "block-id-slot:" + blockId;
    }

    private String heightSlotIndexKey(long height) {
        return "block-height-slot:" + String.format("%020d", height);
    }

    private String transactionSlotKey(String transactionId) {
        return "tx-slot:" + transactionId;
    }

    private String transactionBlockKey(String transactionId) {
        return "tx-block:" + transactionId;
    }

    private String metaKey(String key) {
        return "meta:" + key;
    }

    private long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }
}
