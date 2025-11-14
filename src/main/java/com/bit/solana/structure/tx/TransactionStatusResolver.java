package com.bit.solana.structure.tx;


import java.util.ArrayList;
import java.util.List;

/**
 * 交易状态解析器，用于解析Transaction类中的short类型状态字段
 * 每个状态使用2的幂值表示，通过位运算实现多状态组合
 */
public class TransactionStatusResolver {

    // 状态常量定义（2的0次方开始）
    public static final short UNSUBMITTED = 1 << 0;  // 1 (2^0)：未提交
    public static final short SUBMITTED = 1 << 1;    // 2 (2^1)：已提交
    public static final short PROCESSING = 1 << 2;   // 4 (2^2)：处理中
    public static final short CONFIRMED = 1 << 3;    // 8 (2^3)：已确认
    public static final short FAILED = 1 << 4;       // 16 (2^4)：失败
    public static final short DROPPED = 1 << 5;      // 32 (2^5)：已丢弃
    public static final short REPLACED = 1 << 6;     // 64 (2^6)：已替换
    public static final short UNCONFIRMED = 1 << 7;  // 128 (2^7)：未确认

    // 常用状态组合
    public static final short PENDING = SUBMITTED | PROCESSING | UNCONFIRMED;  // 待确认状态
    public static final short TERMINAL = CONFIRMED | FAILED | DROPPED | REPLACED;  // 终态

    /**
     * 检查交易是否包含目标状态
     * @param transaction 交易对象
     * @param targetStatus 目标状态（如SUBMITTED）
     * @return true=包含该状态
     */
    public static boolean hasStatus(Transaction transaction, short targetStatus) {
        validateStatus(targetStatus);
        return (transaction.getStatus() & targetStatus) != 0;
    }

    /**
     * 为交易添加一个状态
     * @param transaction 交易对象
     * @param newStatus 要添加的状态
     */
    public static void addStatus(Transaction transaction, short newStatus) {
        validateStatus(newStatus);
        short currentStatus = transaction.getStatus();
        transaction.setStatus((short) (currentStatus | newStatus));
    }

    /**
     * 从交易中移除一个状态
     * @param transaction 交易对象
     * @param statusToRemove 要移除的状态
     */
    public static void removeStatus(Transaction transaction, short statusToRemove) {
        validateStatus(statusToRemove);
        short currentStatus = transaction.getStatus();
        transaction.setStatus((short) (currentStatus & ~statusToRemove));
    }

    /**
     * 清空交易的所有状态
     * @param transaction 交易对象
     */
    public static void clearAllStatus(Transaction transaction) {
        transaction.setStatus((short) 0);
    }

    /**
     * 检查交易是否处于终态（不再变化的状态）
     * @param transaction 交易对象
     * @return true=已处于终态
     */
    public static boolean isTerminal(Transaction transaction) {
        return (transaction.getStatus() & TERMINAL) != 0;
    }

    /**
     * 检查交易状态是否存在冲突（互斥状态同时存在）
     * @param transaction 交易对象
     * @return true=存在冲突
     */
    public static boolean hasConflict(Transaction transaction) {
        short status = transaction.getStatus();

        // 已确认与失败/丢弃/替换互斥
        boolean hasConfirmed = (status & CONFIRMED) != 0;
        boolean hasFailedOrTerminal = (status & (FAILED | DROPPED | REPLACED)) != 0;
        if (hasConfirmed && hasFailedOrTerminal) {
            return true;
        }

        // 未提交与已提交/处理中/未确认互斥
        boolean hasUnsubmitted = (status & UNSUBMITTED) != 0;
        boolean hasSubmittedOrProcessing = (status & (SUBMITTED | PROCESSING | UNCONFIRMED)) != 0;
        return hasUnsubmitted && hasSubmittedOrProcessing;
    }

    /**
     * 将交易状态转换为可读字符串
     * @param transaction 交易对象
     * @return 状态描述字符串，如"[SUBMITTED, PROCESSING]"
     */
    public static String getStatusString(Transaction transaction) {
        short status = transaction.getStatus();
        if (status == 0) {
            return "[NONE]";
        }

        List<String> statusList = new ArrayList<>();
        if ((status & UNSUBMITTED) != 0) statusList.add("UNSUBMITTED");
        if ((status & SUBMITTED) != 0) statusList.add("SUBMITTED");
        if ((status & PROCESSING) != 0) statusList.add("PROCESSING");
        if ((status & CONFIRMED) != 0) statusList.add("CONFIRMED");
        if ((status & FAILED) != 0) statusList.add("FAILED");
        if ((status & DROPPED) != 0) statusList.add("DROPPED");
        if ((status & REPLACED) != 0) statusList.add("REPLACED");
        if ((status & UNCONFIRMED) != 0) statusList.add("UNCONFIRMED");

        return "[" + String.join(", ", statusList) + "]";
    }

    /**
     * 验证状态是否为合法的2的幂值
     * @param status 要验证的状态值
     * @throws IllegalArgumentException 当状态值不合法时抛出
     */
    private static void validateStatus(short status) {
        if (status <= 0 || (status & (status - 1)) != 0) {
            throw new IllegalArgumentException("无效的状态值: " + status + "（必须是2的幂）");
        }
    }


    public static void main(String[] args) {
        // 创建交易实例
        Transaction transaction = new Transaction();

        // 初始状态
        System.out.println("初始状态: " + TransactionStatusResolver.getStatusString(transaction));

        // 添加状态
        TransactionStatusResolver.addStatus(transaction, TransactionStatusResolver.UNSUBMITTED);
        System.out.println("添加未提交状态: " + TransactionStatusResolver.getStatusString(transaction));

        // 交易提交
        TransactionStatusResolver.removeStatus(transaction, TransactionStatusResolver.UNSUBMITTED);
        TransactionStatusResolver.addStatus(transaction, TransactionStatusResolver.SUBMITTED);
        TransactionStatusResolver.addStatus(transaction, TransactionStatusResolver.PROCESSING);
        System.out.println("提交后状态: " + TransactionStatusResolver.getStatusString(transaction));

        // 检查是否包含某个状态
        boolean isProcessing = TransactionStatusResolver.hasStatus(transaction, TransactionStatusResolver.PROCESSING);
        System.out.println("是否处理中: " + isProcessing);

        // 交易确认
        TransactionStatusResolver.removeStatus(transaction, TransactionStatusResolver.PROCESSING);
        TransactionStatusResolver.addStatus(transaction, TransactionStatusResolver.CONFIRMED);
        System.out.println("确认后状态: " + TransactionStatusResolver.getStatusString(transaction));

        // 检查是否为终态
        boolean isTerminal = TransactionStatusResolver.isTerminal(transaction);
        System.out.println("是否为终态: " + isTerminal);
    }
}