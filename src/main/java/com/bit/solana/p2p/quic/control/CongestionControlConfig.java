package com.bit.solana.p2p.quic.control;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * QUIC拥塞控制配置和使用案例
 * 提供不同场景下的拥塞控制参数配置
 */
@Slf4j
@Data
public class CongestionControlConfig {
    
    /**
     * 拥塞控制场景枚举
     */
    public enum CongestionScenario {
        HIGH_SPEED_LAN,      // 高速局域网（1Gbps+）
        BROADBAND,          // 宽带网络（100Mbps）
        MOBILE,             // 移动网络（4G/5G）
        SATELLITE,          // 卫星网络（高延迟）
        DATA_CENTER,        // 数据中心（低延迟高带宽）
        WIRELESS,          // 无线网络（不稳定）
        CONSTRAINED         // 受限网络（低带宽）
    }
    
    // 配置参数
    private final long initialCwnd;
    private final long minCwnd;
    private final long maxCwnd;
    private final long slowStartThreshold;
    private final double lossBeta;
    private final double cubicC;
    private final double bbrHighGain;
    private final double bbrDrainGain;
    private final int rttSpikeThreshold;
    private final double lossRateThreshold;
    
    /**
     * 根据场景创建配置
     */
    public static CongestionControlConfig forScenario(CongestionScenario scenario) {
        switch (scenario) {
            case HIGH_SPEED_LAN:
                return createHighSpeedLanConfig();
            case BROADBAND:
                return createBroadbandConfig();
            case MOBILE:
                return createMobileConfig();
            case SATELLITE:
                return createSatelliteConfig();
            case DATA_CENTER:
                return createDataCenterConfig();
            case WIRELESS:
                return createWirelessConfig();
            case CONSTRAINED:
                return createConstrainedConfig();
            default:
                return createDefaultConfig();
        }
    }
    
    /**
     * 高速局域网配置（1Gbps+，延迟<1ms）
     */
    private static CongestionControlConfig createHighSpeedLanConfig() {
        log.info("创建高速局域网拥塞控制配置");
        return new CongestionControlConfig(
            64 * 1024,        // 初始拥塞窗口 64KB
            16 * 1024,        // 最小拥塞窗口 16KB
            50 * 1024 * 1024, // 最大拥塞窗口 50MB
            10 * 1024 * 1024, // 慢启动阈值 10MB
            0.7,              // 丢包减少因子
            0.4,              // CUBIC缩放常数
            2.885,            // BBR高增益
            0.35,              // BBR排水增益
            10,                // RTT突增阈值 10ms
            0.01               // 丢包率阈值 1%
        );
    }
    
    /**
     * 宽带网络配置（100Mbps，延迟20-50ms）
     */
    private static CongestionControlConfig createBroadbandConfig() {
        log.info("创建宽带网络拥塞控制配置");
        return new CongestionControlConfig(
            32 * 1024,        // 初始拥塞窗口 32KB
            8 * 1024,         // 最小拥塞窗口 8KB
            10 * 1024 * 1024, // 最大拥塞窗口 10MB
            2 * 1024 * 1024,  // 慢启动阈值 2MB
            0.7,              // 丢包减少因子
            0.4,              // CUBIC缩放常数
            2.885,            // BBR高增益
            0.35,              // BBR排水增益
            50,                // RTT突增阈值 50ms
            0.02               // 丢包率阈值 2%
        );
    }
    
    /**
     * 移动网络配置（4G/5G，延迟50-200ms）
     */
    private static CongestionControlConfig createMobileConfig() {
        log.info("创建移动网络拥塞控制配置");
        return new CongestionControlConfig(
            24 * 1024,        // 初始拥塞窗口 24KB
            4 * 1024,         // 最小拥塞窗口 4KB
            5 * 1024 * 1024,  // 最大拥塞窗口 5MB
            1 * 1024 * 1024,  // 慢启动阈值 1MB
            0.8,              // 丢包减少因子（更保守）
            0.3,              // CUBIC缩放常数（更小）
            2.5,              // BBR高增益（更小）
            0.4,               // BBR排水增益
            100,               // RTT突增阈值 100ms
            0.03               // 丢包率阈值 3%
        );
    }
    
    /**
     * 卫星网络配置（高延迟500-2000ms）
     */
    private static CongestionControlConfig createSatelliteConfig() {
        log.info("创建卫星网络拥塞控制配置");
        return new CongestionControlConfig(
            48 * 1024,        // 初始拥塞窗口 48KB（更大）
            16 * 1024,        // 最小拥塞窗口 16KB
            20 * 1024 * 1024, // 最大拥塞窗口 20MB
            5 * 1024 * 1024,  // 慢启动阈值 5MB
            0.5,              // 丢包减少因子（更保守）
            0.2,              // CUBIC缩放常数（更小）
            3.0,               // BBR高增益（更大）
            0.2,               // BBR排水增益
            200,               // RTT突增阈值 200ms
            0.05               // 丢包率阈值 5%
        );
    }
    
    /**
     * 数据中心配置（低延迟高带宽）
     */
    private static CongestionControlConfig createDataCenterConfig() {
        log.info("创建数据中心拥塞控制配置");
        return new CongestionControlConfig(
            100 * 1024,       // 初始拥塞窗口 100KB
            32 * 1024,        // 最小拥塞窗口 32KB
            100 * 1024 * 1024, // 最大拥塞窗口 100MB
            20 * 1024 * 1024, // 慢启动阈值 20MB
            0.6,              // 丢包减少因子（更激进）
            0.6,               // CUBIC缩放常数（更大）
            3.5,               // BBR高增益（更大）
            0.3,               // BBR排水增益
            5,                 // RTT突增阈值 5ms
            0.005              // 丢包率阈值 0.5%
        );
    }
    
    /**
     * 无线网络配置（不稳定，可能丢包）
     */
    private static CongestionControlConfig createWirelessConfig() {
        log.info("创建无线网络拥塞控制配置");
        return new CongestionControlConfig(
            16 * 1024,        // 初始拥塞窗口 16KB（更小）
            4 * 1024,         // 最小拥塞窗口 4KB
            2 * 1024 * 1024,  // 最大拥塞窗口 2MB
            512 * 1024,       // 慢启动阈值 512KB
            0.85,             // 丢包减少因子（非常保守）
            0.2,               // CUBIC缩放常数（很小）
            2.0,               // BBR高增益（更小）
            0.5,               // BBR排水增益
            150,               // RTT突增阈值 150ms
            0.08               // 丢包率阈值 8%
        );
    }
    
    /**
     * 受限网络配置（低带宽，高延迟）
     */
    private static CongestionControlConfig createConstrainedConfig() {
        log.info("创建受限网络拥塞控制配置");
        return new CongestionControlConfig(
            8 * 1024,         // 初始拥塞窗口 8KB
            2 * 1024,         // 最小拥塞窗口 2KB
            1 * 1024 * 1024,  // 最大拥塞窗口 1MB
            256 * 1024,       // 慢启动阈值 256KB
            0.9,               // 丢包减少因子（极其保守）
            0.15,              // CUBIC缩放常数（极小）
            1.8,               // BBR高增益（更小）
            0.6,               // BBR排水增益
            300,               // RTT突增阈值 300ms
            0.1                // 丢包率阈值 10%
        );
    }
    
    /**
     * 默认配置
     */
    private static CongestionControlConfig createDefaultConfig() {
        log.info("创建默认拥塞控制配置");
        return new CongestionControlConfig(
            10 * 1024,        // 初始拥塞窗口 10KB
            2 * 1024,         // 最小拥塞窗口 2KB
            10 * 1024 * 1024, // 最大拥塞窗口 10MB
            Long.MAX_VALUE,     // 慢启动阈值 无限制
            0.7,              // 丢包减少因子
            0.4,              // CUBIC缩放常数
            2.885,            // BBR高增益
            0.35,              // BBR排水增益
            200,               // RTT突增阈值 200ms
            0.02               // 丢包率阈值 2%
        );
    }
    
    /**
     * 构造函数
     */
    public CongestionControlConfig(long initialCwnd, long minCwnd, long maxCwnd,
                              long slowStartThreshold, double lossBeta, double cubicC,
                              double bbrHighGain, double bbrDrainGain,
                              int rttSpikeThreshold, double lossRateThreshold) {
        this.initialCwnd = initialCwnd;
        this.minCwnd = minCwnd;
        this.maxCwnd = maxCwnd;
        this.slowStartThreshold = slowStartThreshold;
        this.lossBeta = lossBeta;
        this.cubicC = cubicC;
        this.bbrHighGain = bbrHighGain;
        this.bbrDrainGain = bbrDrainGain;
        this.rttSpikeThreshold = rttSpikeThreshold;
        this.lossRateThreshold = lossRateThreshold;
    }
    
    /**
     * 应用配置到拥塞控制器
     */
    public void applyToController(QuicCongestionControl controller) {
        log.info("应用拥塞控制配置到连接 {}: {}", 
                 controller.getConnectionId(), this.toString());
        
        // 这里需要扩展QuicCongestionControl以支持配置应用
        // 目前打印配置信息作为演示
        log.info("配置参数: 初始Cwnd={}KB, 最小Cwnd={}KB, 最大Cwnd={}MB, " +
                 "丢包率阈值={:.1f}%, RTT突增阈值={}ms",
                 initialCwnd / 1024, minCwnd / 1024, maxCwnd / (1024 * 1024),
                 lossRateThreshold * 100, rttSpikeThreshold);
    }
    
    @Override
    public String toString() {
        return String.format(
            "CongestionControlConfig{initialCwnd=%dKB, minCwnd=%dKB, maxCwnd=%dMB, " +
            "slowStartThreshold=%dKB, lossBeta=%.2f, cubicC=%.2f, " +
            "bbrHighGain=%.3f, bbrDrainGain=%.2f, rttSpikeThreshold=%dms, " +
            "lossRateThreshold=%.3f}",
            initialCwnd / 1024, minCwnd / 1024, maxCwnd / (1024 * 1024),
            slowStartThreshold / 1024, lossBeta, cubicC,
            bbrHighGain, bbrDrainGain, rttSpikeThreshold, lossRateThreshold
        );
    }
}