package com.bit.solana.p2p.quic.control;

/**
 * 拥塞控制算法接口
 * 定义拥塞控制的核心行为和参数
 */
public interface CongestionControl {
    
    /**
     * 数据包发送成功确认
     * @param ackedBytes 确认的字节数
     * @param rtt 往返时间（毫秒）
     */
    void onAck(long ackedBytes, long rtt);
    
    /**
     * 数据包丢失
     * @param lostBytes 丢失的字节数
     */
    void onPacketLoss(long lostBytes);
    
    /**
     * 获取当前拥塞窗口大小（字节）
     */
    long getCongestionWindow();
    
    /**
     * 获取当前发送速率（字节/秒）
     */
    long getSendRate();
    
    /**
     * 设置最大发送速率
     * @param maxRate 最大速率（字节/秒）
     */
    void setMaxSendRate(long maxRate);
    
    /**
     * 检查是否可以发送指定大小的数据
     * @param dataSize 数据大小
     * @return 是否可以发送
     */
    boolean canSend(long dataSize);
    
    /**
     * 更新发送速率（基于时间）
     */
    void updateSendRate();
    
    /**
     * 重置拥塞控制状态
     */
    void reset();
    
    /**
     * 获取拥塞控制状态信息
     */
    CongestionState getState();
}