package com.bit.solana.poh.impl;

import com.bit.solana.structure.poh.POHRecord;
import com.bit.solana.poh.POHService;
import com.bit.solana.structure.poh.PohEntry;
import com.bit.solana.structure.tx.Transaction;
import com.bit.solana.util.ByteUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.bitcoinj.core.Sha256Hash;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;


/**
 * POH服务实现类
 * 使用SHA-256哈希算法实现历史证明链
 */
@Slf4j
@Service
public class POHServiceImpl implements POHService {
    @Autowired
    private POHEngineImpl pohEngine;

    @Override
    public POHRecord appendEvent(byte[] eventData) {
        return pohEngine.appendEvent(eventData).getData();
    }

    @Override
    public Transaction generateTimestamp(Transaction transaction) {
        try {
            // 为交易生成POH时间戳
            POHRecord record = pohEngine.timestampTransaction(transaction).getData();

            // 这里可以将POH记录与交易关联，例如添加到交易的扩展字段
            // 假设Transaction有一个setPohRecord方法
            if (record != null) {
                transaction.setPohRecord(record);
                log.debug("为交易{}生成POH时间戳，序列号: {}",
                        ByteUtils.bytesToHex(transaction.getTxId()),
                        record.getSequenceNumber());
            }

            return transaction;
        } catch (Exception e) {
            log.error("为交易生成POH时间戳失败", e);
            return transaction;
        }
    }

    @Override
    public List<Transaction> batchGenerateTimestamp(List<Transaction> transactions) {
        if (transactions == null || transactions.isEmpty()) {
            return transactions;
        }

        // 批量生成时间戳
        List<POHRecord> records = pohEngine.batchTimestampTransactions(transactions).getData();

        // 关联POH记录与交易（假设Transaction有setPohRecord方法）
        if (records != null && records.size() == transactions.size()) {
            for (int i = 0; i < transactions.size(); i++) {
                Transaction tx = transactions.get(i);
                POHRecord record = records.get(i);
                if (record != null && tx != null) {
                    // tx.setPohRecord(record); // 如果允许修改structure包，可添加此方法
                }
            }
        }

        return transactions;
    }

    @Override
    public boolean verifyPohChain(PohEntry entry) {
        // 验证POH单个节点在链中的合法性：检查当前哈希是否由前序哈希、计数器及关联数据正确生成
        try {
            // 1. 校验必要字段非空
            if (entry == null
                    || entry.getHash() == null
                    || entry.getPreviousHash() == null
                    || entry.getCounter() < 0) {
                log.warn("POH条目字段不完整，验证失败");
                return false;
            }

            // 2. 将哈希字符串（Hex编码）转换为字节数组（POH底层基于字节计算）
            byte[] currentHashBytes;
            byte[] previousHashBytes;
            try {
                currentHashBytes = Hex.decodeHex(entry.getHash()); // 假设使用Apache Commons Codec的Hex工具
                previousHashBytes = Hex.decodeHex(entry.getPreviousHash());
            } catch (DecoderException e) {
                log.error("POH哈希值格式错误（非Hex编码）", e);
                return false;
            }

            // 3. 提取用于计算当前哈希的原始数据
            // 3.1 计数器转换为字节数组（8字节，用于哈希计算）
            byte[] counterBytes = ByteUtils.longToBytes(entry.getCounter());

            // 3.2 交易相关数据的哈希（非空事件时需要，空事件时为null）
            byte[] transactionHashBytes = null;
            if (entry.getTransactionId() != null && !entry.getTransactionId().isEmpty()) {
                // 若有交易ID，使用交易ID的哈希作为事件数据（符合POH仅存哈希的设计）
                transactionHashBytes = Sha256Hash.hash(entry.getTransactionId().getBytes(StandardCharsets.UTF_8));
            }

            // 4. 重新计算当前哈希（模拟POH引擎的哈希生成逻辑）
            byte[] computedHash = computePohHash(previousHashBytes, counterBytes, transactionHashBytes);

            // 5. 比对计算出的哈希与条目自身的哈希是否一致
            boolean isValid = Arrays.equals(currentHashBytes, computedHash);
            if (!isValid) {
                log.warn("POH条目哈希验证失败：计算值={}, 实际值={}",
                        Hex.encodeHexString(computedHash),
                        entry.getHash());
            }
            return isValid;

        } catch (Exception e) {
            log.error("POH链验证发生异常", e);
            return false;
        }
    }



    /**
     * POH引擎的哈希计算逻辑：currentHash = SHA-256(previousHash + counter + transactionHash)
     * - 空事件（无交易）：transactionHash为null，仅用previousHash + counter
     * - 非空事件（有交易）：包含previousHash + counter + transactionHash
     */
    private byte[] computePohHash(byte[] previousHash, byte[] counterBytes, byte[] transactionHash) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            // 累加前序哈希
            digest.update(previousHash);
            // 累加计数器
            digest.update(counterBytes);
            // 若有交易哈希，累加交易哈希（非空事件）
            if (transactionHash != null) {
                digest.update(transactionHash);
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256算法不支持", e); // 理论上不会发生
        }
    }

    @Override
    public String getLatestHash() {
        return ByteUtils.bytesToHex(pohEngine.getLastHash());
    }

    @Override
    public POHRecord getCurrentPOH() {
        return null;
    }
}
