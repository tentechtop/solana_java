package com.bit.solana.api;

import com.bit.solana.monitor.SystemMonitor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.bit.solana.monitor.impl.dto.*;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/monitor/system")
public class SystemMonitorApi {


    @Autowired
    private SystemMonitor systemMonitor;

    /**
     * 获取CPU监控数据
     */
    @GetMapping("/cpu")
    public CpuMetrics getCpu() {
        return systemMonitor.getCpuMetrics();
    }

    /**
     * 获取JVM内存监控数据
     */
    @GetMapping("/jvm/memory")
    public JvmMemoryMetrics getJvmMemory() {
        return systemMonitor.getJvmMemoryMetrics();
    }

    /**
     * 获取系统物理内存监控数据
     */
    @GetMapping("/memory")
    public SystemMemoryMetrics getSystemMemory() {
        return systemMonitor.getSystemMemoryMetrics();
    }

    /**
     * 获取磁盘监控数据
     */
    @GetMapping("/disk")
    public List<DiskMetrics> getDisk() {
        return systemMonitor.getDiskMetrics();
    }

    /**
     * 获取网络监控数据
     */
    @GetMapping("/network")
    public List<NetworkMetrics> getNetwork() {
        return systemMonitor.getNetworkMetrics();
    }
}