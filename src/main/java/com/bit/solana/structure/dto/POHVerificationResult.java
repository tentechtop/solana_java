package com.bit.solana.structure.dto;

import com.bit.solana.poh.POHRecord;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * POH记录验证结果封装类
 * 包含验证状态、错误信息和排序后的记录（验证通过时）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class POHVerificationResult {
    private boolean success;          // 验证是否通过
    private String message;           // 错误信息（验证失败时）
    private List<POHRecord> sortedRecords;  // 排序后的记录（验证通过时）
}