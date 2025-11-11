package com.bit.solana.voting.impl;

import com.bit.solana.voting.VotingService;
import org.springframework.stereotype.Component;

/**
 * 职责：
 * 处理共识流程（提议、预投票、预提交、确认）；
 * 维护投票状态（使用ConcurrentHashMap存储验证者投票，参考白皮书）；
 * 实现容错逻辑（锁定机制：对高度 H 预提交后，拒绝 H' < H 的投票）。
 * 关键依赖：
 * POH 服务（获取时序戳，确保投票顺序）；
 * 区块存储（验证区块合法性）；
 * 网络模块（广播投票信息给其他验证者）。
 */
@Component
public class VotingServiceImpl implements VotingService {
}
