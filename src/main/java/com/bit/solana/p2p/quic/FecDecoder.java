package com.bit.solana.p2p.quic;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * FEC解码器（XOR冗余恢复）
 * 支持规则：
 * 1. 每组包含 N 个数据帧 + 1 个冗余帧（N = QuicConstants.FEC_REDUNDANCY_RATIO）
 * 2. 最多可恢复 1 个丢失的帧（数据帧/冗余帧）
 * 3. 丢失超过1帧时无法恢复，直接清理分组
 */
@Component
public class FecDecoder {
    // 存储待恢复的FEC分组 <groupId, 按fecIndex排序的帧列表>
    private final Map<Integer, List<QuicFrame>> fecGroups = new ConcurrentHashMap<>();

    // 冗余帧的索引（固定为数据帧数量）
    private static final int REDUNDANCY_FRAME_INDEX = QuicConstants.FEC_REDUNDANCY_RATIO;

    /**
     * 解码并尝试恢复丢失的帧
     * @param fecFrame 收到的帧（数据帧/冗余帧）
     * @return 恢复后的完整帧列表（无丢失/恢复成功），空列表表示无法恢复
     */
    public List<QuicFrame> decode(QuicFrame fecFrame) {
        // 基础校验
        if (fecFrame == null || fecFrame.getPayload() == null || !fecFrame.getPayload().isReadable()) {
            return Collections.emptyList();
        }

        int groupId = fecFrame.getFecGroupId();
        int frameIndex = fecFrame.getFecIndex();

        // 校验索引合法性（防止非法帧）
        if (frameIndex < 0 || frameIndex > REDUNDANCY_FRAME_INDEX) {
            return Collections.emptyList();
        }

        // 1. 将帧加入对应分组（按索引排序存储）
        List<QuicFrame> group = fecGroups.computeIfAbsent(groupId, k -> new ArrayList<>());
        synchronized (group) { // 加锁保证并发安全
            // 去重（避免重复接收同一帧）
            boolean isDuplicate = group.stream()
                    .anyMatch(f -> f.getFecIndex() == frameIndex);
            if (!isDuplicate) {
                group.add(fecFrame);
                // 按fecIndex排序，方便后续索引检查
                group.sort((f1, f2) -> Integer.compare(f1.getFecIndex(), f2.getFecIndex()));
            }

            // 2. 检查是否满足恢复条件
            // 恢复条件：收到的帧数量 >= 数据帧数量（N），且最多丢失1帧
            int receivedCount = group.size();
            int requiredDataCount = QuicConstants.FEC_REDUNDANCY_RATIO;
            int maxLostCount = 1;
            int totalFrameCount = requiredDataCount + 1; // 数据帧+冗余帧总数

            // 2.1 未满足恢复条件，直接返回空
            if (receivedCount < requiredDataCount) {
                return Collections.emptyList();
            }

            // 2.2 丢失超过1帧，无法恢复，清理分组并返回空
            if (totalFrameCount - receivedCount > maxLostCount) {
                fecGroups.remove(groupId);
                releaseGroupFrames(group); // 释放内存
                return Collections.emptyList();
            }

            // 3. 识别丢失的帧索引
            List<Integer> existingIndexes = group.stream()
                    .map(QuicFrame::getFecIndex)
                    .collect(Collectors.toList());
            Integer lostIndex = null;
            for (int i = 0; i < totalFrameCount; i++) {
                if (!existingIndexes.contains(i)) {
                    lostIndex = i;
                    break;
                }
            }

            // 4. 无丢失帧，直接返回完整数据帧
            if (lostIndex == null) {
                List<QuicFrame> dataFrames = group.stream()
                        .filter(f -> f.getFecIndex() < requiredDataCount)
                        .collect(Collectors.toList());
                fecGroups.remove(groupId); // 清理分组
                return dataFrames;
            }

            // 5. 恢复丢失的帧
            try {
                return recoverLostFrame(group, groupId, lostIndex, requiredDataCount);
            } catch (Exception e) {
                // 恢复失败，清理分组并释放资源
                fecGroups.remove(groupId);
                releaseGroupFrames(group);
                throw new RuntimeException("FEC帧恢复失败, groupId=" + groupId, e);
            }
        }
    }

    /**
     * 恢复丢失的帧
     * @param group 已收到的帧列表
     * @param groupId FEC组ID
     * @param lostIndex 丢失帧的索引
     * @param requiredDataCount 数据帧数量
     * @return 恢复后的完整数据帧列表
     */
    private List<QuicFrame> recoverLostFrame(List<QuicFrame> group, int groupId,
                                             Integer lostIndex, int requiredDataCount) {
        // 5.1 对所有已收到帧的payload做XOR运算，得到丢失帧的payload
        ByteBuf lostPayload = xorAllByteBuf(group);
        if (lostPayload == null || !lostPayload.isReadable()) {
            releaseGroupFrames(group);
            return Collections.emptyList();
        }

        // 5.2 构造恢复后的帧
        QuicFrame recoveredFrame = QuicFrame.acquire();
        // 从同组帧复制基础字段（保证连接/流信息一致）
        QuicFrame referenceFrame = group.get(0);
        recoveredFrame.setConnectionId(referenceFrame.getConnectionId());
        recoveredFrame.setStreamId(referenceFrame.getStreamId());
        recoveredFrame.setFrameType(referenceFrame.getFrameType());
        recoveredFrame.setFecGroupId(groupId);
        recoveredFrame.setFecIndex(lostIndex);
        recoveredFrame.setRemoteAddress(referenceFrame.getRemoteAddress());
        recoveredFrame.setPayload(lostPayload);
        // 复制其他关键元数据
        recoveredFrame.setPriority(referenceFrame.getPriority());
        recoveredFrame.setQpsLimit(referenceFrame.getQpsLimit());
        recoveredFrame.setFileRegion(referenceFrame.isFileRegion());
        recoveredFrame.setFileOffset(referenceFrame.getFileOffset());
        recoveredFrame.setFileLength(referenceFrame.getFileLength());
        recoveredFrame.setFileName(referenceFrame.getFileName());

        // 5.3 将恢复的帧加入分组，重新排序
        group.add(recoveredFrame);
        group.sort((f1, f2) -> Integer.compare(f1.getFecIndex(), f2.getFecIndex()));

        // 5.4 提取完整的数据帧（过滤冗余帧）
        List<QuicFrame> fullDataFrames = group.stream()
                .filter(f -> f.getFecIndex() < requiredDataCount)
                .collect(Collectors.toList());

        // 5.5 清理分组和冗余帧（释放内存）
        fecGroups.remove(groupId);
        // 释放冗余帧（只保留数据帧）
        group.stream()
                .filter(f -> f.getFecIndex() == REDUNDANCY_FRAME_INDEX)
                .forEach(QuicFrame::release);

        return fullDataFrames;
    }

    /**
     * 对一组帧的payload进行全量XOR运算
     * @param frames 帧列表
     * @return 异或结果（需手动释放），null表示无有效数据
     */
    private ByteBuf xorAllByteBuf(List<QuicFrame> frames) {
        ByteBuf result = null;
        try {
            for (QuicFrame frame : frames) {
                ByteBuf payload = frame.getPayload();
                if (payload == null || !payload.isReadable()) {
                    continue;
                }

                // 保存原始读取索引
                int readerIndex = payload.readerIndex();
                payload.readerIndex(0);

                if (result == null) {
                    // 初始化结果为第一个有效payload的拷贝
                    result = payload.copy();
                } else {
                    // 逐字节异或（取最小长度）
                    int minLength = Math.min(result.readableBytes(), payload.readableBytes());
                    ByteBuf tempResult = Unpooled.buffer(minLength);

                    for (int i = 0; i < minLength; i++) {
                        byte b1 = result.readByte();
                        byte b2 = payload.readByte();
                        tempResult.writeByte(b1 ^ b2);
                    }

                    // 释放旧结果，替换为新结果
                    result.release();
                    result = tempResult;
                    // 重置读写索引
                    result.readerIndex(0);
                    result.writerIndex(minLength);
                }

                // 恢复原始读取索引
                payload.readerIndex(readerIndex);
            }
            return result;
        } catch (Exception e) {
            if (result != null) {
                result.release();
            }
            return null;
        }
    }

    /**
     * 释放分组内所有帧的资源（防止内存泄漏）
     * @param group 帧分组
     */
    private void releaseGroupFrames(List<QuicFrame> group) {
        if (group == null || group.isEmpty()) {
            return;
        }
        group.forEach(frame -> {
            try {
                frame.release();
            } catch (Exception e) {
                // 忽略释放异常，避免影响主流程
                e.printStackTrace();
            }
        });
    }

    /**
     * 手动清理指定分组（用于超时/异常处理）
     * @param groupId FEC组ID
     */
    public void clearGroup(int groupId) {
        List<QuicFrame> group = fecGroups.remove(groupId);
        releaseGroupFrames(group);
    }

    /**
     * 清理所有分组（用于连接关闭）
     */
    public void clearAllGroups() {
        fecGroups.values().forEach(this::releaseGroupFrames);
        fecGroups.clear();
    }

    /**
     * 获取当前分组数量（用于监控/调试）
     */
    public int getGroupCount() {
        return fecGroups.size();
    }
}