package com.bit.solana.api;

import com.bit.solana.monitor.SystemMonitor;
import com.bit.solana.monitor.impl.dto.CpuMetrics;
import com.bit.solana.monitor.impl.dto.JvmMemoryMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/monitor/system")
public class SystemMonitorApi {

    @Autowired
    private SystemMonitor systemMonitor;

    @GetMapping("/cpu")
    public CpuMetrics getCpu() {
        return systemMonitor.getCpuMetrics();
    }

    @GetMapping("/jvm/memory")
    public JvmMemoryMetrics getJvmMemory() {
        return systemMonitor.getJvmMemoryMetrics();
    }



}
