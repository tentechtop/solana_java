package com.bit.solana.sentinel;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.init.InitFunc;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeException;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import java.util.ArrayList;
import java.util.List;

// 实现 InitFunc 接口，在应用启动时初始化规则
public class SentinelInit implements InitFunc {
    @Override
    public void init() throws Exception {
        // 初始化限流规则
        initFlowRules();
        // 初始化熔断规则
        initCircuitBreakerRules();
    }

    // 限流规则：限制接口的 QPS
    private void initFlowRules() {
        List<FlowRule> rules = new ArrayList<>();

        //限流一
        FlowRule rule = new FlowRule();
        // 资源名：通常是接口方法名或唯一标识
        rule.setResource("orderService:getOrder");
        // 限流阈值类型：QPS 模式
        rule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        // 限流阈值：每秒最多 10 个请求
        rule.setCount(10);
        rules.add(rule);

        //限流二


        FlowRuleManager.loadRules(rules);
    }

    /**
     * 核心参数：
     * grade：熔断触发类型（3 种）：
     * DEGRADE_GRADE_EXCEPTION_RATIO：异常比例（默认）。
     * DEGRADE_GRADE_EXCEPTION_COUNT：异常次数。
     * DEGRADE_GRADE_SLOW_REQUEST_RATIO：慢调用比例（响应时间超过阈值的请求占比）。
     * timeWindow：熔断后进入 “开放期” 的时间（秒），期间请求直接降级，不调用原资源。
     */
    // 初始化熔断规则（正确写法）
    private void initCircuitBreakerRules() {
        List<DegradeRule> rules = new ArrayList<>();

        //规则一
        DegradeRule rule = new DegradeRule();
        rule.setResource("paymentService:pay"); // 保护的资源名
        // 熔断触发条件：按异常比例（异常请求占比）
        rule.setGrade(RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO);
        rule.setCount(0.5); // 异常比例阈值：50%（超过则熔断）
        rule.setTimeWindow(10); // 熔断时长：10秒（期间不调用该资源）
        rule.setMinRequestAmount(20); // 最小请求数：至少20个请求才判断异常比例
        rule.setStatIntervalMs(1000); // 统计窗口：1秒内的请求
        rules.add(rule);

        //规则二
        // 新增规则：orderService:createOrder（示例）
        DegradeRule createOrderRule = new DegradeRule();
        createOrderRule.setResource("orderService:createOrder"); // 接口资源名
        createOrderRule.setGrade(RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO); // 异常比例
        createOrderRule.setCount(0.5); // 异常比例阈值50%
        createOrderRule.setTimeWindow(10); // 熔断10秒
        createOrderRule.setMinRequestAmount(20); // 至少20个请求
        createOrderRule.setStatIntervalMs(1000); // 1秒统计窗口
        rules.add(createOrderRule); // 添加到规则列表
        DegradeRuleManager.loadRules(rules); // 加载熔断规则
    }
}