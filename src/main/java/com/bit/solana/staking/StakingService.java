package com.bit.solana.staking;

/**
 * 核心类：StakingService（接口） + StakingServiceImpl（实现）
 * 职责：
 * 验证者质押 / 解质押操作（修改账户状态）；
 * 质押权重计算（结合代币数量与节点性能）；
 * 验证者列表管理（动态加入 / 退出，基于智能合约逻辑）。
 * 关键依赖：
 * 账户存储（读取 / 修改质押账户余额）；
 * 智能合约引擎（若需通过合约管理质押规则）。
 */
public interface StakingService {

    // 质押J-SOL成为验证者

    // 解质押并退出验证者

    // 获取验证者权重列表

}
