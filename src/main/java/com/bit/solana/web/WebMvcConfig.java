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


    }
}