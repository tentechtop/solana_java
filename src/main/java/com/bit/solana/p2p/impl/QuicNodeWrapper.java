package com.bit.solana.p2p.impl;


import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.concurrent.*;


import static com.bit.solana.util.ByteUtils.bytesToHex;

/**
 * 随用随建是其最优使用方式
 */

@Data
@Slf4j
public class QuicNodeWrapper {
    private byte[] nodeId; // 节点ID 公钥的base58编码
    private String address;
    private int port;


    private boolean isOutbound; // 是否为主动出站连接
    private boolean active;

    //最后更新时间
    private long lastSeen;
    // 连接过期阈值（秒）：默认5秒，无活动则判定为过期
    private int expireSeconds = 300;

    //衍生
    private InetSocketAddress inetSocketAddress;

    // 心跳任务实例
    private ScheduledFuture<?> heartbeatTask;//心跳任务异常仅影响单个节点，不扩散（流隔离的延伸）。


    // 仅持有全局调度器引用，不自己创建
    private final ScheduledExecutorService globalScheduler;
    public QuicNodeWrapper(ScheduledExecutorService globalScheduler) {
        this.globalScheduler = Objects.requireNonNull(globalScheduler, "全局调度器不能为空");

    }
    // 禁止空构造器（避免误使用）
    private QuicNodeWrapper() {
        throw new UnsupportedOperationException("必须注入全局调度器，禁止无参构造");
    }

    public void startHeartbeat(long intervalSeconds,byte[] localId) {
        try {
            // 1. 取消原有任务（避免重复调度）
            stopHeartbeat();
            // 3. 提交周期性心跳任务到全局调度器
            heartbeatTask = globalScheduler.scheduleAtFixedRate(
                    () -> executeHeartbeat(localId), // 心跳执行逻辑
                    0, // 初始延迟：立即执行第一次
                    intervalSeconds, // 周期间隔
                    TimeUnit.SECONDS // 时间单位
            );

            log.info("节点{}心跳任务已启动，间隔{}秒，调度器：{}",
                    bytesToHex(nodeId), intervalSeconds, globalScheduler);
        }catch (Exception e){
            throw new RuntimeException("心跳发生错误",e);
        }
    }


    /**
     * 执行单次心跳逻辑（封装为独立方法，便于调度执行）
     * @param localId 本地节点ID
     */
    private void executeHeartbeat(byte[] localId) {

    }



    // 关闭连接
    public void close() {

    }



    // 停止当前节点的心跳任务
    public void stopHeartbeat() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
            heartbeatTask = null;
            log.info("节点{}心跳任务已取消", nodeId);
        }
    }




    // 检查连接是否活跃（通道活跃+未过期）
    public boolean isActive() {

        // 2. 再检查是否过期（核心：结合lastSeen判断）
        return !isExpired();
    }

    /**
     * 设置节点活跃状态
     * @param active 活跃状态
     */
    public void setActive(boolean active) {
        // 状态未变化时直接返回，避免无效操作
        if (this.active == active) {
            return;
        }
        // 更新状态
        this.active = active;
        if (!active){
            close();
        }
    }

    public InetSocketAddress getInetSocketAddress() {
        if (inetSocketAddress == null){
            inetSocketAddress = new InetSocketAddress(address, port);
        }
        return inetSocketAddress;
    }

    /**
     * 检查连接是否过期
     * @return true=过期，false=未过期
     */
    public boolean isExpired() {
        // 无最后更新时间 → 视为过期
        if (lastSeen <= 0) {
            return true;
        }
        // 计算超时时间：当前时间 - 最后更新时间 > 过期阈值（毫秒）
        long expireMillis = expireSeconds * 1000L;
        return (System.currentTimeMillis() - lastSeen) > expireMillis;
    }

    /**
     * 更新最后活动时间（线程安全）
     */
    public void updateLastSeen() {
        this.lastSeen = System.currentTimeMillis();
    }
}
