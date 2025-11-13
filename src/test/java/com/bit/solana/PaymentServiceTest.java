package com.bit.solana;

import com.bit.solana.sentinel.PaymentService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.CountDownLatch;


@Slf4j
@SpringBootTest
public class PaymentServiceTest {

    @Autowired
    private PaymentService PaymentService;




    @Test
    void testCircuitBreaker() throws InterruptedException {
        // 1. 超高并发触发异常（确保5秒内集中出现3次以上异常）
        int threadCount = 10; // 10个线程同时压测
        CountDownLatch startLatch = new CountDownLatch(1); // 统一开始信号
        CountDownLatch endLatch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                try {
                    startLatch.await(); // 等待统一开始
                    // 每个线程连续发送3次请求（总30次，高频次）
                    for (int j = 0; j < 3; j++) {
                        try {
                            String result = PaymentService.pay(1L);
                            System.out.println(Thread.currentThread().getName() + "：" + result);
                        } catch (Exception e) {
                            System.out.println(Thread.currentThread().getName() + "：" + e.getMessage());
                        }
                        // 间隔缩短到50ms，确保3次请求在150ms内完成
                        Thread.sleep(50);
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                } finally {
                    endLatch.countDown();
                }
            }).start();
        }

        // 统一触发所有线程（瞬间产生30次请求）
        startLatch.countDown();
        endLatch.await(); // 等待所有请求完成（约200ms）

        // 2. 立即测试熔断是否触发
        System.out.println("\n===== 熔断后测试 =====");
        for (int i = 0; i < 3; i++) {
            String result = PaymentService.pay(100L);
            System.out.println("熔断后请求" + i + "：" + result);
            Thread.sleep(100); // 间隔100ms，确保在熔断窗口期内
        }
    }

}
