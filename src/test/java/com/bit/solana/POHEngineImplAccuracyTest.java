package com.bit.solana;

import com.bit.solana.structure.poh.POHRecord;
import com.bit.solana.poh.impl.POHEngineImpl;
import com.bit.solana.result.Result;
import com.bit.solana.structure.dto.POHVerificationResult;
import com.bit.solana.structure.tx.Transaction;
import com.bit.solana.util.ByteUtils;
import com.bit.solana.util.Sha;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static com.bit.solana.util.ByteUtils.combine;
import static org.junit.jupiter.api.Assertions.*;

@Slf4j
@SpringBootTest
public class POHEngineImplAccuracyTest {

    @Autowired
    private POHEngineImpl pohEngine;



    /**
     * 核心测试：事件追加+交易时间戳+记录验证（无干扰环境）
     */
    @Test
    void testEventAppendAndVerification() {

        byte[] bytes = ByteUtils.longToBytes(2);
        log.info("字节长度{}",bytes.length);


        // 步骤1：添加3个普通事件
        byte[] bytes1 = "test-event-1".getBytes();
        byte[] bytes2 = "test-event-1".getBytes();
        byte[] bytes3 = "test-event-1".getBytes();


        // 步骤3：添加1个交易事件
        Transaction testTx = new Transaction();
        byte[] txData = "test-transaction-data".getBytes();
        testTx.setTxId(Sha.applySHA256(txData));
        Result<POHRecord> txResult = pohEngine.timestampTransaction(testTx);
        assertTrue(txResult.isSuccess(), "交易事件添加失败：" + txResult.getMessage());
        POHRecord data = txResult.getData();
        log.info("POH数据{}",data);
        Result<Boolean> booleanResult = pohEngine.verifyRecord(data);
        log.info("验证结果{}",booleanResult);

    }


    @Test
    void testEventOrderAndSorting() {
        // 1. 准备一批测试事件（模拟无序提交，实际按顺序提交但记录顺序由POH序号决定）
        List<byte[]> testEvents = new ArrayList<>();
        testEvents.add("event-1".getBytes());    // 预期顺序1
        testEvents.add("event-2".getBytes());    // 预期顺序2
        testEvents.add("event-3".getBytes());    // 预期顺序3
        testEvents.add("event-4".getBytes());    // 预期顺序4
        testEvents.add("event-5".getBytes());    // 预期顺序5

        // 2. 为每个事件添加POH时间戳，记录返回的POHRecord
        List<POHRecord> pohRecords = new ArrayList<>();
        List<String> originalOrder = new ArrayList<>(); // 记录原始事件内容，用于排序后验证

        for (byte[] event : testEvents) {
            Result<POHRecord> result = pohEngine.appendEvent(event);
            assertTrue(result.isSuccess(), "事件添加失败：" + result.getMessage());
            pohRecords.add(result.getData());
            originalOrder.add(new String(event)); // 保存原始事件内容
        }

        // 3. 验证所有POH记录的连续性（哈希链、序号递增等）
        Result<Boolean> verifyResult = pohEngine.verifyRecords(pohRecords);
        assertTrue(verifyResult.isSuccess(), "POH记录连续性验证失败：" + verifyResult.getMessage());

        // 4. 提取POH记录中的序号和对应的事件内容，按序号排序
        List<POHRecord> sortedRecords = new ArrayList<>(pohRecords);
        sortedRecords.sort((r1, r2) -> Long.compare(r1.getSequenceNumber(), r2.getSequenceNumber()));

        // 5. 验证排序后的事件顺序与原始提交顺序一致（通过事件内容确认）
        List<String> sortedEvents = new ArrayList<>();
        for (POHRecord record : sortedRecords) {
            // 从POH记录的eventHash反向计算事件内容（实际场景中应关联原始事件，这里简化处理）
            // 注意：eventHash是事件内容的哈希，这里通过原始事件列表匹配哈希对应的内容
            byte[] eventHash = record.getEventHash();
            for (byte[] event : testEvents) {
                byte[] calculatedHash = Sha.applySHA256(event);
                if (Arrays.equals(eventHash, calculatedHash)) {
                    sortedEvents.add(new String(event));
                    break;
                }
            }
        }

        // 6. 断言排序结果与原始提交顺序一致
        assertEquals(originalOrder, sortedEvents, "POH排序结果与原始事件顺序不一致");
        log.info("事件顺序验证通过，排序结果与原始顺序一致");
    }



    /**
     * 测试1：正常场景 - 记录顺序正确，验证通过并返回排序结果
     */
    @Test
    void testVerifyAndSortWithCorrectOrder() {
        // 1. 生成5条顺序正确的事件记录
        List<POHRecord> records = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            byte[] eventData = ("test-event-" + i).getBytes();
            Result<POHRecord> result = pohEngine.appendEvent(eventData);
            assertTrue(result.isSuccess(), "事件" + i + "生成失败: " + result.getMessage());
            records.add(result.getData());
        }

        // 2. 调用验证排序方法（输入顺序正确）
        POHVerificationResult verificationResult = pohEngine.verifyAndSortRecords(records);

        // 3. 验证结果
        assertTrue(verificationResult.isSuccess(), "验证失败: " + verificationResult.getMessage());
        assertNotNull(verificationResult.getSortedRecords(), "排序后记录为空");
        assertEquals(5, verificationResult.getSortedRecords().size(), "排序后记录数量异常");

        // 4. 验证排序后的序号严格递增
        List<Long> sortedSeqs = verificationResult.getSortedRecords().stream()
                .map(POHRecord::getSequenceNumber)
                .collect(Collectors.toList());
        for (int i = 1; i < sortedSeqs.size(); i++) {
            assertTrue(sortedSeqs.get(i) > sortedSeqs.get(i - 1),
                    "排序后序号未递增: " + sortedSeqs.get(i - 1) + " -> " + sortedSeqs.get(i));
        }
        log.info("正常顺序验证通过，排序结果正确");
    }

    /**
     * 测试2：输入顺序混乱但属于同一POH链 - 验证通过并返回正确排序
     */
    @Test
    void testVerifyAndSortWithShuffledOrder() {
        // 1. 生成5条记录
        List<POHRecord> records = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            byte[] eventData = ("shuffled-event-" + i).getBytes();
            records.add(pohEngine.appendEvent(eventData).getData());
        }

        // 2. 打乱输入顺序
        Collections.shuffle(records);
        List<Long> shuffledSeqs = records.stream()
                .map(POHRecord::getSequenceNumber)
                .collect(Collectors.toList());
        log.info("打乱后的序号: {}", shuffledSeqs);

        // 3. 调用验证排序方法
        POHVerificationResult verificationResult = pohEngine.verifyAndSortRecords(records);

        // 4. 验证结果
        assertTrue(verificationResult.isSuccess(), "乱序记录验证失败: " + verificationResult.getMessage());
        assertNotNull(verificationResult.getSortedRecords(), "排序后记录为空");

        // 5. 验证排序后序号连续递增（与原始生成顺序一致）
        List<Long> sortedSeqs = verificationResult.getSortedRecords().stream()
                .map(POHRecord::getSequenceNumber)
                .collect(Collectors.toList());
        log.info("排序后的序号: {}", sortedSeqs);
        for (int i = 1; i < sortedSeqs.size(); i++) {
            assertTrue(sortedSeqs.get(i) > sortedSeqs.get(i - 1),
                    "乱序排序后序号未递增: " + sortedSeqs.get(i - 1) + " -> " + sortedSeqs.get(i));
        }
        log.info("乱序记录验证通过，排序结果正确");
    }

    /**
     * 测试3：包含哈希错误的记录 - 验证失败
     */
    @Test
    void testVerifyAndSortWithInvalidHash() {
        // 1. 生成2条正常记录
        POHRecord validRecord1 = pohEngine.appendEvent("valid-1".getBytes()).getData();
        POHRecord validRecord2 = pohEngine.appendEvent("valid-2".getBytes()).getData();

        // 2. 篡改第2条记录的哈希（制造非法记录）
        POHRecord invalidRecord = new POHRecord();
        invalidRecord.setPreviousHash(validRecord2.getPreviousHash()); // 复用合法前置哈希
        invalidRecord.setEventHash(validRecord2.getEventHash());
        invalidRecord.setSequenceNumber(validRecord2.getSequenceNumber());
        invalidRecord.setCurrentHash(new byte[32]); // 篡改当前哈希为全0

        // 3. 组合记录列表（包含1条非法记录）
        List<POHRecord> records = List.of(validRecord1, invalidRecord);

        // 4. 调用验证方法
        POHVerificationResult result = pohEngine.verifyAndSortRecords(records);

        // 5. 验证结果（应失败）
        assertFalse(result.isSuccess(), "哈希错误的记录验证应失败");
        assertTrue(result.getMessage().contains("哈希验证失败"),
                "错误信息不符合预期: " + result.getMessage());
        assertNull(result.getSortedRecords(), "非法记录不应返回排序结果");
        log.info("哈希错误记录验证失败，符合预期");
    }

    /**
     * 测试4：包含序号重复的记录 - 验证失败
     */
    @Test
    void testVerifyAndSortWithDuplicateSequence() {
        // 1. 生成第一条正常记录
        POHRecord original = pohEngine.appendEvent("original".getBytes()).getData();
        long originalSeq = original.getSequenceNumber();
        byte[] originalPrevHash = original.getPreviousHash().clone(); // 保存原始前置哈希

        // 2. 手动构造第二条记录，确保哈希合法且序号与第一条相同
        POHRecord duplicateSeqRecord = new POHRecord();
        duplicateSeqRecord.setSequenceNumber(originalSeq); // 强制重复序号
        duplicateSeqRecord.setPreviousHash(originalPrevHash); // 复用前置哈希
        byte[] eventData = "duplicate-seq".getBytes();
        byte[] eventHash = Sha.applySHA256(eventData);
        duplicateSeqRecord.setEventHash(eventHash);

        // 手动计算当前哈希（确保与序号匹配，哈希验证通过）
        byte[] currentHash = originalPrevHash.clone();
        // 复现哈希计算逻辑（与appendEvent一致）
        for (int i = 0; i < 12500; i++) { // 对应HASHES_PER_TICK
            currentHash = Sha.applySHA256(combine(currentHash, ByteUtils.longToBytes(i), originalSeq));
        }
        byte[] finalHash = Sha.applySHA256(combine(currentHash, eventHash, originalSeq));
        duplicateSeqRecord.setCurrentHash(finalHash);


        // 3. 组合记录列表
        List<POHRecord> records = List.of(original, duplicateSeqRecord);

        // 4. 调用验证方法
        POHVerificationResult result = pohEngine.verifyAndSortRecords(records);

        // 5. 验证结果（应因序号重复失败）
        assertFalse(result.isSuccess(), "序号重复的记录验证应失败");
        assertTrue(result.getMessage().contains("序号不递增"),
                "错误信息不符合预期: " + result.getMessage());
        log.info("序号重复记录验证失败，符合预期");
    }



    /**
     * 测试5：记录属于不同POH链（哈希链断裂）- 验证失败
     */
    @Test
    void testVerifyAndSortWithBrokenChain() {
        // 1. 生成2条正常记录（属于同一条链）
        POHRecord record1 = pohEngine.appendEvent("chain-1-event-1".getBytes()).getData();
        POHRecord record2 = pohEngine.appendEvent("chain-1-event-2".getBytes()).getData();

        // 2. 生成一条属于"另一条链"的记录（手动修改前置哈希，制造断裂）
        POHRecord brokenRecord = new POHRecord();
        brokenRecord.setPreviousHash(new byte[32]); // 前置哈希改为全0（与正常链无关）
        brokenRecord.setEventHash(Sha.applySHA256("broken-event".getBytes()));
        brokenRecord.setSequenceNumber(record2.getSequenceNumber() + 1); // 序号合法
        // 手动计算当前哈希（确保单条记录哈希合法，但与主链断裂）
        byte[] iterHash = brokenRecord.getPreviousHash().clone();
        for (int i = 0; i < 12500; i++) { // 复用常量HASHES_PER_TICK
            iterHash = Sha.applySHA256(combine(iterHash, ByteUtils.longToBytes(i), brokenRecord.getSequenceNumber()));
        }
        byte[] currentHash = Sha.applySHA256(combine(iterHash, brokenRecord.getEventHash(), brokenRecord.getSequenceNumber()));
        brokenRecord.setCurrentHash(currentHash);

        // 3. 组合记录列表（包含断裂记录）
        List<POHRecord> records = List.of(record1, record2, brokenRecord);

        // 4. 调用验证方法
        POHVerificationResult result = pohEngine.verifyAndSortRecords(records);

        // 5. 验证结果（应失败）
        assertFalse(result.isSuccess(), "链断裂的记录验证应失败");
        assertTrue(result.getMessage().contains("与全局POH链断裂"),
                "错误信息不符合预期: " + result.getMessage());
        log.info("链断裂记录验证失败，符合预期");
    }
}