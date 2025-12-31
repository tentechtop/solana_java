# 智能流量控制系统使用指南

## 核心特性

### 1. 自我观测系统
- **实时监控**：每2秒收集一次网络状态数据
- **多维度观测**：带宽利用率、丢包率、RTT、活跃连接数
- **历史数据**：保留最近30次观测（1分钟历史）

### 2. 平滑调节机制
- **状态确认**：需要连续3次相同状态才执行调节
- **平滑限制**：单次调节幅度不超过上次的20%
- **温和策略**：避免频繁抖动，保持稳定

### 3. 自我恢复能力
- **卡住检测**：5秒无新数据发送触发检测
- **自动恢复**：连续3次检测到卡住，自动重置控制器
- **安全降速**：恢复后降低到安全速率，避免再次卡住

## 使用方法

### 基础使用

```java
// 1. 获取实例
IntelligentGlobalFlowControl control = IntelligentGlobalFlowControl.getInstance();

// 2. 设置全局带宽限制
control.updateGlobalBandwidth(100 * 1024 * 1024); // 100MB/s

// 3. 发送数据（三层检查）
IntelligentGlobalFlowControl.OptimizedFlowControlResult result =
    control.trySend(connectionId, ipAddress, dataSize);

if (result.isAllowed()) {
    // 发送成功
} else {
    // 被限流
    System.out.println("限流原因: " + result.getReason());
}

// 4. 更新RTT（用于BBR探测）
control.updateConnectionRtt(connectionId, rtt);

// 5. 清理连接
control.removeConnection(connectionId);
```

### 运行演示

```bash
# 智能调节演示（推荐）
java com.bit.solana.p2p.quic.control.IntelligentDemo
```

**演示内容：**
- 阶段1：正常发送（20秒）
- 阶段2：超速发送触发限流（15秒）
- 阶段3：恢复正常（10秒）
- 阶段4：模拟卡住（5秒）- 触发自我恢复
- 阶段5：恢复正常（10秒）

**预期输出：**
```
>>> 阶段1: 正常发送 (0-20秒) <<<
[5.0s] 网络状态监控
  利用率: 75% | 当前速率: 15.12 MB/s | 丢包率: 0.12%
  连接数: 1 | IP数: 1 | 连续丢包: 0

[平滑调节] 状态:NORMAL (连续3次) | 利用率:78% | 丢包率:0.15% | 策略:正常状态，微调 | 全局:1.05x | 连接:1.05x

>>> 阶段2: 超速发送，触发限流 (20-35秒) <<<
[限流] 连接流量限制
[限流] 全局流量限制

[平滑调节] 状态:CONGESTED (连续3次) | 利用率:98% | 丢包率:5.23% | 策略:检测到拥塞，积极降速 | 全局:0.85x | 连接:0.9x

>>> 阶段4: 模拟卡住 (45-50秒) - 等待自我恢复 <<<
[自我恢复] 检测到可能卡住 (1/3), 空闲时间:5012ms, 活跃连接:1
[自我恢复] 检测到可能卡住 (2/3), 空闲时间:10024ms, 活跃连接:1
[自我恢复] 检测到可能卡住 (3/3), 空闲时间:15036ms, 活跃连接:1
[自我恢复] 触发自我恢复机制!
[自我恢复] 重置连接: 1001
[自我恢复] 恢复完成，速率已降低到安全水平
```

## 平滑调节机制详解

### 状态分类

| 状态 | 利用率 | 丢包率 | 调节策略 |
|------|--------|--------|----------|
| UNDERUTILIZED | < 60% | 任意 | 提速 15% |
| OPTIMAL | ≥ 85% | < 2% | 保持不变 |
| NORMAL | 60-85% | 任意 | 微调 5% |
| CONGESTED | > 95% 或 > 5% 或 连续丢包>3次 | 任意 | 降速 15% |

### 平滑机制

1. **状态确认**：需要连续3次观测到相同状态才触发调节
2. **幅度限制**：单次调节幅度不超过上次的20%
3. **温和调节**：避免大起大落，保持平滑

**示例：**
```
观测序列：
  [T1] NORMAL (利用率70%) -> 暂不调节
  [T2] NORMAL (利用率72%) -> 暂不调节
  [T3] NORMAL (利用率75%) -> 触发调节 (微调+5%)
  [T4] OPTIMAL (利用率88%) -> 重新计数
  [T5] OPTIMAL (利用率87%) -> 暂不调节
```

## 自我恢复机制详解

### 触发条件

1. **卡住检测**：
   - 5秒内无新数据发送
   - 连续检测3次确认卡住

2. **恢复动作**：
   - 重置全局令牌桶
   - 重置所有连接控制器
   - 降低到安全速率（最大带宽的25%）

### 恢复策略

```java
// 检测逻辑
if (当前发送量 == 上次发送量 && 空闲时间 > 5000ms) {
    stuckCounter++;
    if (stuckCounter >= 3) {
        performRecovery(); // 执行恢复
    }
}

// 恢复逻辑
void performRecovery() {
    // 1. 重置所有控制器
    globalController.reset();
    connectionControllers.forEach((id, ctrl) -> ctrl.reset());
    
    // 2. 降低到安全速率
    globalController.updateSendRate(maxGlobalBandwidth / 4);
}
```

## 配置参数

### 观测间隔
- 默认：2000ms（2秒）
- 调节：`control.setObservationInterval(1000);`

### 调节间隔
- 默认：5000ms（5秒）
- 固定值，不可修改

### 平滑阈值
- 默认：连续3次相同状态
- 常量：`STATE_CONFIRMATION_THRESHOLD = 3`

### 恢复超时
- 默认：5000ms（5秒）
- 常量：`STUCK_TIMEOUT_MS = 5000`

## 获取观测数据

```java
IntelligentGlobalFlowControl.ObservationData obs = control.getObservationData();

if (obs != null) {
    System.out.println("带宽利用率: " + obs.getBandwidthUtilization() + "%");
    System.out.println("丢包率: " + obs.getDropRate() + "%");
    System.out.println("平均RTT: " + obs.getAvgRtt() + "ms");
    System.out.println("活跃连接: " + obs.getActiveConnections());
}
```

## 获取全局统计

```java
IntelligentGlobalFlowControl.OptimizedGlobalStats stats = control.getGlobalStats();

System.out.println("总发送: " + stats.getTotalBytesSent() / 1024 / 1024 + " MB");
System.out.println("总接收: " + stats.getTotalBytesReceived() / 1024 / 1024 + " MB");
System.out.println("丢弃: " + stats.getDroppedBytes() / 1024 / 1024 + " MB");
System.out.println("当前速率: " + stats.getCurrentGlobalRate() / 1024 / 1024 + " MB/s");
System.out.println("利用率: " + stats.getBandwidthUtilization() + "%");
```

## 控制自动调节

```java
// 禁用自动调节
control.setAutoTuning(false);

// 启用自动调节
control.setAutoTuning(true);
```

## 关闭系统

```java
// 关闭观测和调节线程
control.shutdown();
```

## 性能指标

### 优秀标准
- 带宽利用率：≥ 85%
- 丢包率：< 1%
- 自我恢复：能自动从卡住状态恢复

### 良好标准
- 带宽利用率：≥ 70%
- 丢包率：< 3%
- 平滑调节：无明显抖动

## 故障排查

### 问题1：调节太频繁
**原因**：状态不稳定，频繁切换  
**解决**：增加 `STATE_CONFIRMATION_THRESHOLD` 值

### 问题2：恢复太慢
**原因**：`STUCK_TIMEOUT_MS` 设置太大  
**解决**：减少超时时间到 3000ms

### 问题3：调节太保守
**原因**：平滑限制太严  
**解决**：调整 `createSmoothStrategy` 中的平滑系数

## 总结

智能流量控制系统通过以下机制提升性能：

1. **自我观测**：实时了解网络状态
2. **平滑调节**：避免频繁抖动，保持稳定
3. **自我恢复**：自动检测并解决卡住问题
4. **三层控制**：全局/IP/连接多层保护
5. **智能决策**：基于历史数据的多层决策

运行 `IntelligentDemo` 可以直观看到这些机制的实际效果！
