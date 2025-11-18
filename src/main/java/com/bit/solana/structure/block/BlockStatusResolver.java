package com.bit.solana.structure.block;

import java.util.ArrayList;
import java.util.List;

/**
 * 区块状态解析器（位运算实现，支持多状态共存）
 * 每个状态用2的幂值表示，通过位运算实现状态的组合、判断和转换
 */
public class BlockStatusResolver {
    // 状态定义（2的0次方开始，每个状态占用1个比特位）
    public static final int UNVERIFIED = 1 << 0;   // 1 (2^0)：未验证
    public static final int VERIFIED = 1 << 1;     // 2 (2^1)：已验证
    public static final int CONFIRMED = 1 << 2;    // 4 (2^2)：已共识确认
    public static final int ARCHIVED = 1 << 3;     // 8 (2^3)：已归档
    public static final int INVALID = 1 << 4;      // 16 (2^4)：无效
                                                   // 非主链

    // 常用状态组合
    public static final int PENDING = UNVERIFIED | VERIFIED;  // 待确认状态（未验证或已验证但未确认）
    public static final int TERMINAL = CONFIRMED | ARCHIVED | INVALID;  // 终态集合

    /**
     * 检查状态集合中是否包含目标状态
     * @param statusBits 状态集合（位运算组合结果）
     * @param targetStatus 目标状态（如VERIFIED）
     * @return true=包含该状态
     */
    public static boolean hasStatus(int statusBits, int targetStatus) {
        validateStatus(targetStatus);
        return (statusBits & targetStatus) != 0;
    }

    /**
     * 向状态集合中添加一个状态
     * @param statusBits 原状态集合
     * @param newStatus 要添加的状态
     * @return 新的状态集合
     */
    public static int addStatus(int statusBits, int newStatus) {
        validateStatus(newStatus);
        return statusBits | newStatus;
    }

    /**
     * 从状态集合中移除一个状态
     * @param statusBits 原状态集合
     * @param statusToRemove 要移除的状态
     * @return 新的状态集合
     */
    public static int removeStatus(int statusBits, int statusToRemove) {
        validateStatus(statusToRemove);
        return statusBits & ~statusToRemove;
    }

    /**
     * 清空所有状态
     * @return 空状态集合（0）
     */
    public static int clearAll() {
        return 0;
    }

    /**
     * 判断是否为终态（无需再处理的状态）
     * @param statusBits 状态集合
     * @return true=终态（包含CONFIRMED/ARCHIVED/INVALID）
     */
    public static boolean isTerminal(int statusBits) {
        return (statusBits & TERMINAL) != 0;
    }

    /**
     * 判断是否为有效状态（未失效且未归档）
     * @param statusBits 状态集合
     * @return true=有效（包含UNVERIFIED/VERIFIED/CONFIRMED）
     */
    public static boolean isValid(int statusBits) {
        return hasStatus(statusBits, UNVERIFIED)
                || hasStatus(statusBits, VERIFIED)
                || hasStatus(statusBits, CONFIRMED);
    }

    /**
     * 检查状态集合是否存在冲突（互斥状态同时存在）
     * 例如：一个区块不能同时是"已验证"和"无效"
     * @param statusBits 状态集合
     * @return true=存在冲突
     */
    public static boolean hasConflict(int statusBits) {
        // 有效状态（UNVERIFIED/VERIFIED/CONFIRMED）与无效状态（INVALID）互斥
        boolean hasValid = isValid(statusBits);
        boolean hasInvalid = hasStatus(statusBits, INVALID);
        if (hasValid && hasInvalid) {
            return true;
        }

        // 未验证（UNVERIFIED）与已验证（VERIFIED）互斥
        return hasStatus(statusBits, UNVERIFIED) && hasStatus(statusBits, VERIFIED);
    }

    /**
     * 将状态集合转换为可读字符串
     * @param statusBits 状态集合
     * @return 状态描述字符串，如"[VERIFIED, CONFIRMED]"
     */
    public static String toString(int statusBits) {
        if (statusBits == 0) {
            return "[NONE]";
        }

        List<String> statusList = new ArrayList<>();
        if (hasStatus(statusBits, UNVERIFIED)) statusList.add("UNVERIFIED");
        if (hasStatus(statusBits, VERIFIED)) statusList.add("VERIFIED");
        if (hasStatus(statusBits, CONFIRMED)) statusList.add("CONFIRMED");
        if (hasStatus(statusBits, ARCHIVED)) statusList.add("ARCHIVED");
        if (hasStatus(statusBits, INVALID)) statusList.add("INVALID");

        return "[" + String.join(", ", statusList) + "]";
    }

    /**
     * 验证状态是否为合法的2的幂值（确保是单一状态）
     * @param status 要验证的状态
     * @throws IllegalArgumentException 当状态不合法时抛出
     */
    private static void validateStatus(int status) {
        if (status <= 0 || (status & (status - 1)) != 0) {
            throw new IllegalArgumentException("无效的状态值: " + status + "（必须是2的幂）");
        }
    }

    public static void main(String[] args) {
        // 1. 初始化区块状态（刚接收，未验证）
        int blockStatus = BlockStatusResolver.clearAll();
        blockStatus = BlockStatusResolver.addStatus(blockStatus, BlockStatusResolver.UNVERIFIED);
        System.out.println("1. 刚接收的区块状态: " + BlockStatusResolver.toString(blockStatus));
        System.out.println("   是否为终态: " + BlockStatusResolver.isTerminal(blockStatus) + "（预期：false）");
        System.out.println("   是否有效: " + BlockStatusResolver.isValid(blockStatus) + "（预期：true）\n");

        // 2. 完成验证（移除未验证，添加已验证）
        blockStatus = BlockStatusResolver.removeStatus(blockStatus, BlockStatusResolver.UNVERIFIED);
        blockStatus = BlockStatusResolver.addStatus(blockStatus, BlockStatusResolver.VERIFIED);
        System.out.println("2. 验证后的区块状态: " + BlockStatusResolver.toString(blockStatus));
        System.out.println("   是否包含已验证状态: " + BlockStatusResolver.hasStatus(blockStatus, BlockStatusResolver.VERIFIED) + "（预期：true）\n");

        // 3. 达成共识确认（添加已确认状态）
        blockStatus = BlockStatusResolver.addStatus(blockStatus, BlockStatusResolver.CONFIRMED);
        System.out.println("3. 共识确认后的状态: " + BlockStatusResolver.toString(blockStatus));
        System.out.println("   是否为终态: " + BlockStatusResolver.isTerminal(blockStatus) + "（预期：true）\n");

        // 4. 归档处理（添加已归档状态）
        blockStatus = BlockStatusResolver.addStatus(blockStatus, BlockStatusResolver.ARCHIVED);
        System.out.println("4. 归档后的状态: " + BlockStatusResolver.toString(blockStatus));
        System.out.println("   是否包含已归档: " + BlockStatusResolver.hasStatus(blockStatus, BlockStatusResolver.ARCHIVED) + "（预期：true）\n");

        // 5. 模拟冲突状态（例如：同时标记为已验证和无效）
        int conflictStatus = BlockStatusResolver.addStatus(BlockStatusResolver.VERIFIED, BlockStatusResolver.INVALID);
        System.out.println("5. 冲突状态示例: " + BlockStatusResolver.toString(conflictStatus));
        System.out.println("   是否存在冲突: " + BlockStatusResolver.hasConflict(conflictStatus) + "（预期：true）");
    }
}