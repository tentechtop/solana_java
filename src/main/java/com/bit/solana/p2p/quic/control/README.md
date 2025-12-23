# QUIC拥塞控制和流量控制模块

## 概述

本模块为QUIC可靠UDP传输系统提供了完整的拥塞控制和流量控制功能，确保网络传输的高效性和稳定性。

## 核心组件

### 1. 拥塞控制算法

#### CongestionControl (接口)
定义了拥塞控制的核心行为，支持多种拥塞控制算法的切换。

#### CubicCongestionControl (实现)
- **算法**: CUBIC拥塞控制算法
- **适用场景**: 高带宽、高延迟网络环境
- **特性**: 
  - 慢启动阶段
  - 拥塞避免阶段（CUBIC函数）
  - 快速恢复机制
  - RTT公平性保证

### 2. 流量控制

#### FlowController
基于**令牌桶算法**实现：
- **流量整形**: 平滑突发流量
- **速率限制**: 控制最大发送速率
- **突发处理**: 允许短暂的数据突发

### 3. 集成管理

#### CongestionFlowManager
协调拥塞控制和流量控制器：
- **自动调优**: 根据拥塞状态动态调整发送速率
- **定期监控**: 每30秒输出详细状态报告
- **异常处理**: 完整的异常恢复机制

#### QuicConnectionControl
为QUIC连接提供统一的控制接口：
- **连接级别控制**: 每个连接独立的拥塞控制
- **RTT跟踪**: 实时计算往返时间
- **超时检测**: 智能的网络异常检测

## 使用方式

### 基本使用

```java
// 1. 创建连接控制
QuicConnectionControl control = new QuicConnectionControl(connectionId);

// 2. 检查发送权限
if (control.canSend(dataSize)) {
    control.onPacketSent(dataSize);
    // 发送数据...
}

// 3. 处理ACK
control.onAckReceived(ackedBytes, originalSize);

// 4. 处理丢包
control.onPacketLoss(lostBytes);
```

### 高级配置

```java
// 设置最大发送速率
control.setMaxSendRate(10 * 1024 * 1024); // 10MB/s

// 获取详细状态
String report = control.getDetailedReport();
log.info(report);
```

### 集成到现有代码

在 `QuicConnection.sendData()` 方法中集成：

```java
public CompletableFuture<Void> sendData(ByteBuf data) {
    // 检查流量控制
    if (!connectionControl.canSend(data.readableBytes())) {
        return CompletableFuture.failedFuture(new RuntimeException("被限流"));
    }
    
    // 获取发送许可（阻塞式）
    if (!connectionControl.acquireSendPermission(data.readableBytes(), 1000)) {
        return CompletableFuture.failedFuture(new RuntimeException("超时"));
    }
    
    // 记录发送
    connectionControl.onPacketSent(data.readableBytes());
    
    // 原有发送逻辑...
}
```

## 参数调优

### 网络环境适配

| 网络类型 | 初始速率 | 最大速率 | 突发大小 | 推荐算法 |
|---------|---------|---------|---------|---------|
| 局域网   | 5MB/s  | 50MB/s | 10MB   | CUBIC   |
| 广域网   | 2MB/s  | 20MB/s | 5MB    | CUBIC   |
| 移动网络 | 1MB/s  | 10MB/s | 2MB    | CUBIC   |
| 卫星网络 | 500KB/s | 5MB/s  | 1MB    | CUBIC   |

### 拥塞参数

```java
// 慢启动阈值
state.setSlowStartThreshold(10 * 1024 * 1024); // 10MB

// 最小拥塞窗口
private static final long MIN_CWND = 2 * 1024; // 2KB

// 最大拥塞窗口
private static final long MAX_CWND = 100 * 1024 * 1024; // 100MB
```

### 流控参数

```java
// 令牌桶参数
long maxBurstSize = 2 * 1024 * 1024;    // 2MB突发
long maxRate = 10 * 1024 * 1024;           // 10MB/s最大
long refillRate = 1024 * 1024;             // 1MB/s补充速率
```

## 监控和诊断

### 关键指标

1. **拥塞窗口大小**: 实时反映网络承载能力
2. **发送速率**: 当前实际发送速率
3. **RTT**: 网络延迟状况
4. **丢包率**: 网络质量指标
5. **令牌利用率**: 流量控制紧张程度

### 状态报告示例

```
=== 拥塞流控状态报告 ===
拥塞窗口: 2.5MB | 发送速率: 3.2MB/s | RTT: 85ms
慢启动: 否 | 拥塞避免: 是 | 恢复: 否
丢包率: 0.02% | 吞吐量: 3.1MB/s
令牌利用率: 65.0% | 速率利用率: 32.0%
统计: 发送=1250, 确认=1245, 丢失=5
```

## 性能优化

### 1. 动态调优
- 自适应拥塞窗口
- 基于RTT的速率调整
- 智能突发处理

### 2. 内存优化
- 对象池复用
- 零拷贝设计
- 及时资源清理

### 3. 并发优化
- 读写锁分离
- 无锁原子操作
- 线程安全保证

## 故障处理

### 常见问题

1. **发送被限流**: 检查发送速率是否过高
2. **RTT异常增大**: 可能网络拥塞
3. **频繁丢包**: 降低发送速率
4. **拥塞窗口过小**: 网络质量差，耐心等待

### 恢复机制

- **自动恢复**: 超时自动重置状态
- **手动干预**: 提供reset()方法
- **优雅降级**: 限制速率但不中断服务

## 扩展性

### 新算法支持

1. 实现 `CongestionControl` 接口
2. 在 `CongestionFlowManager` 中切换
3. 测试验证性能

### 自定义策略

```java
// 实现自定义拥塞控制
public class CustomCongestionControl implements CongestionControl {
    // 实现接口方法...
}

// 使用自定义算法
CongestionFlowManager manager = new CongestionFlowManager();
manager.setCongestionControl(new CustomCongestionControl());
```

## 最佳实践

1. **监控优先**: 持续监控关键指标
2. **参数调优**: 根据实际网络环境调整
3. **异常处理**: 完善的异常恢复机制
4. **性能测试**: 在各种网络条件下测试
5. **定期评估**: 定期评估和优化配置

## 版本历史

- **v1.0**: 基础CUBIC算法实现
- **v1.1**: 添加令牌桶流量控制
- **v1.2**: 集成管理和监控功能
- **v1.3**: 优化性能和稳定性