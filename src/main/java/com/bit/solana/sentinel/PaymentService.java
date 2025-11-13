package com.bit.solana.sentinel;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class PaymentService {

    // 用注解标记资源，无需手动调用 SphU.entry()
    @SentinelResource(
            value = "paymentService:pay", // 资源名（与规则绑定）
            blockHandler = "payBlockHandler", // 熔断/限流时的降级方法
            fallback = "payFallback"

    )
    // fallback = "payFallback" // 业务异常时的兜底方法（可选）降级
    public String pay(Long orderId) {
        // 纯业务逻辑，无任何 Sentinel 相关代码
        if (orderId == null || orderId <= 0) {
            throw new IllegalArgumentException("订单ID无效"); // 业务异常
        }
        // 模拟调用第三方支付（30%概率失败）
        if (Math.random() < 0.5) {
            throw new RuntimeException("第三方支付超时");
        }
        return "订单[" + orderId + "]支付成功";
    }

    // 熔断/限流触发时的降级方法// 限流与阻塞处理
    public String payBlockHandler(Long orderId, BlockException e) {
        log.info("阻塞线路");
        return "payBlockHandler 订单[" + orderId + "]支付失败：系统繁忙，请稍后重试（已触发限流/熔断）";
    }

    // 业务异常时的兜底方法（参数和返回值需与原方法一致）
    public String payFallback(Long orderId, Throwable e) {
        return "payFallback 订单[" + orderId + "]支付失败：" + e.getMessage();
    }
}