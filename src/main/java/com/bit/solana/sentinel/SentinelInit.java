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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Sentinel 对 SPI 的依赖Sentinel 框架在启动时，会通过 SPI 机制寻找 InitFunc 接口的所有实现类，并调用它们的 init() 方法。
 * 你的 SentinelInit 类实现了 InitFunc 接口，并重写了 init() 方法（里面包含限流 / 熔断规则的初始化逻辑）。
 * // 实现 InitFunc 接口，在应用启动时初始化规则
 */
@Slf4j
public class SentinelInit implements InitFunc {
    @Override
    public void init() throws Exception {
        // 初始化限流规则
        initFlowRules();
        log.info("initFlowRules");
        // 初始化熔断规则
        initCircuitBreakerRules();
        log.info("initCircuitBreakerRules");
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
        // 为 paymentService:pay 添加限流规则（关键）
        FlowRule payFlowRule = new FlowRule();
        payFlowRule.setResource("paymentService:pay"); // 资源名与 @SentinelResource 的 value 一致
        payFlowRule.setGrade(RuleConstant.FLOW_GRADE_QPS); // 按 QPS 限流
        payFlowRule.setCount(5); // 阈值：每秒最多 5 次请求（测试时调小，容易触发）
        rules.add(payFlowRule);


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


        // 针对 paymentService:pay 的熔断规则
        DegradeRule payRule = new DegradeRule();
        payRule.setResource("paymentService:pay"); // 与 @SentinelResource 的 value 一致
        payRule.setGrade(RuleConstant.DEGRADE_GRADE_EXCEPTION_COUNT); // 按异常次数熔断
        payRule.setCount(3); // 异常次数阈值：3次
        payRule.setTimeWindow(10); // 熔断时长：10秒（期间请求被拦截）
        payRule.setStatIntervalMs(5000); // 统计窗口：5秒内
        payRule.setMinRequestAmount(3); // 最小请求数：至少3次请求才判断
        rules.add(payRule);


        DegradeRuleManager.loadRules(rules); // 加载熔断规则
    }
}