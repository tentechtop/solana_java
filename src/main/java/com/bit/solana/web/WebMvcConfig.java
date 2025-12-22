package com.bit.solana.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // 配置路径映射：访问 /monitor/SystemMonitor 时，直接返回 static/monitor/dashboard.html
        registry.addViewController("/monitor/system")
                .setViewName("forward:/monitor/SystemMonitor.html");
        registry.addViewController("/solana")
                .setViewName("forward:/home/index.html");
        registry.addViewController("/solana/block")
                .setViewName("forward:/block/index.html");
        registry.addViewController("/solana/account")
                .setViewName("forward:/account/accountAdmin.html");
        registry.addViewController("/solana/txpool")
                .setViewName("forward:/txpool/txPool.html");
        registry.addViewController("/solana/mock")
                .setViewName("forward:/mock/index.html");
        registry.addViewController("/solana/TransferTx")
                .setViewName("forward:/account/TransferTx.html");
        registry.addViewController("/solana/quic/monitor")
                .setViewName("forward:/quic/quic-monitor.html");
        registry.addViewController("/solana/quic/dashboard")
                .setViewName("forward:/quic/quic-dashboard.html");

    }
}