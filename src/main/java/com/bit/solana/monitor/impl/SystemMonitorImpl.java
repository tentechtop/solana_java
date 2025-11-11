package com.bit.solana.monitor.impl;

import com.bit.solana.monitor.SystemMonitor;

import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.hardware.NetworkIF;
import oshi.software.os.FileSystem;
import oshi.software.os.OSFileStore;
import oshi.software.os.OSProcess;
import oshi.software.os.OperatingSystem;
import org.springframework.stereotype.Component;
import com.bit.solana.monitor.impl.dto.*;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class SystemMonitorImpl implements SystemMonitor {

    // Oshi核心工具类
    private final SystemInfo systemInfo = new SystemInfo();
    private final HardwareAbstractionLayer hardware = systemInfo.getHardware();
    private final OperatingSystem os = systemInfo.getOperatingSystem();
    // JVM内存监控Bean
    private final MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
    // 网络指标采样缓存（用于计算速率）
    private long[] lastNetworkBytes;
    private long lastNetworkTime;

    @Override
    public CpuMetrics getCpuMetrics() {
        CpuMetrics cpuMetrics = new CpuMetrics();
        CentralProcessor processor = hardware.getProcessor();

        // 系统CPU使用率（两次采样计算）
        long[] prevTicks = processor.getSystemCpuLoadTicks();
        try {
            TimeUnit.MILLISECONDS.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return cpuMetrics;
        }
        long[] currTicks = processor.getSystemCpuLoadTicks();
        double systemCpuLoad = processor.getSystemCpuLoadBetweenTicks(prevTicks) * 100;
        cpuMetrics.setSystemCpuUsage(round(systemCpuLoad));

        // 当前进程CPU使用率
        OSProcess currentProcess = os.getProcess(os.getProcessId());
        if (currentProcess != null) {
            long processCpuTime = currentProcess.getKernelTime() + currentProcess.getUserTime();
            long upTime = currentProcess.getUpTime();
            if (upTime > 0) {
                double processCpuUsage = (processCpuTime * 1.0 / upTime)
                        / processor.getLogicalProcessorCount() * 100;
                cpuMetrics.setProcessCpuUsage(round(processCpuUsage));
            }
        }

        // CPU核心数
        cpuMetrics.setLogicalCores(processor.getLogicalProcessorCount());
        cpuMetrics.setPhysicalCores(processor.getPhysicalProcessorCount());

        return cpuMetrics;
    }

    @Override
    public JvmMemoryMetrics getJvmMemoryMetrics() {
        JvmMemoryMetrics jvmMetrics = new JvmMemoryMetrics();

        // 堆内存信息
        MemoryUsage heapMemory = memoryMXBean.getHeapMemoryUsage();
        jvmMetrics.setHeapInit(heapMemory.getInit());
        jvmMetrics.setHeapUsed(heapMemory.getUsed());
        jvmMetrics.setHeapCommitted(heapMemory.getCommitted());
        jvmMetrics.setHeapMax(heapMemory.getMax());

        // 非堆内存信息
        MemoryUsage nonHeapMemory = memoryMXBean.getNonHeapMemoryUsage();
        jvmMetrics.setNonHeapInit(nonHeapMemory.getInit());
        jvmMetrics.setNonHeapUsed(nonHeapMemory.getUsed());
        jvmMetrics.setNonHeapCommitted(nonHeapMemory.getCommitted());
        jvmMetrics.setNonHeapMax(nonHeapMemory.getMax());

        return jvmMetrics;
    }

    @Override
    public SystemMemoryMetrics getSystemMemoryMetrics() {
        SystemMemoryMetrics memoryMetrics = new SystemMemoryMetrics();
        GlobalMemory memory = hardware.getMemory();

        memoryMetrics.setTotal(memory.getTotal());
        memoryMetrics.setAvailable(memory.getAvailable());
        memoryMetrics.setUsed(memory.getTotal() - memory.getAvailable());
        memoryMetrics.setUsedPercent(round(100.0 - (memory.getAvailable() * 100.0 / memory.getTotal())));

        return memoryMetrics;
    }

    @Override
    public List<DiskMetrics> getDiskMetrics() {
        List<DiskMetrics> diskMetricsList = new ArrayList<>();
        FileSystem fileSystem = os.getFileSystem();

        for (OSFileStore store : fileSystem.getFileStores()) {
            DiskMetrics diskMetrics = new DiskMetrics();
            diskMetrics.setDevice(store.getName());
            diskMetrics.setTotalSpace(store.getTotalSpace());
            diskMetrics.setFreeSpace(store.getFreeSpace());
            diskMetrics.setUsedSpace(store.getTotalSpace() - store.getFreeSpace());

            double usedPercent = 100.0 - (store.getFreeSpace() * 100.0 / store.getTotalSpace());
            diskMetrics.setUsedPercent(round(usedPercent));

            diskMetricsList.add(diskMetrics);
        }

        return diskMetricsList;
    }

    @Override
    public List<NetworkMetrics> getNetworkMetrics() {
        List<NetworkMetrics> networkMetricsList = new ArrayList<>();
        List<NetworkIF> networkIFs = hardware.getNetworkIFs();
        long currentTime = System.currentTimeMillis();

        // 初始化首次采样数据
        if (lastNetworkBytes == null) {
            lastNetworkBytes = new long[networkIFs.size() * 2]; // 每个网卡2个值：接收、发送
            lastNetworkTime = currentTime;
            int index = 0;
            for (NetworkIF iface : networkIFs) {
                lastNetworkBytes[index++] = iface.getBytesRecv();
                lastNetworkBytes[index++] = iface.getBytesSent();
            }
            return networkMetricsList; // 首次采样不返回数据（无速率）
        }

        // 计算网络速率（基于两次采样差值）
        long timeDiff = currentTime - lastNetworkTime;
        if (timeDiff <= 0) {
            timeDiff = 1; // 避免除零
        }

        int index = 0;
        for (NetworkIF iface : networkIFs) {
            NetworkMetrics networkMetrics = new NetworkMetrics();
            networkMetrics.setInterfaceName(iface.getName());
            networkMetrics.setReceivedBytes(iface.getBytesRecv());
            networkMetrics.setSentBytes(iface.getBytesSent());
            networkMetrics.setReceivedPackets(iface.getPacketsRecv());
            networkMetrics.setSentPackets(iface.getPacketsSent());

            // 计算接收/发送速率（B/s）
            long prevRecv = lastNetworkBytes[index];
            long prevSent = lastNetworkBytes[index + 1];
            double recvRate = (iface.getBytesRecv() - prevRecv) * 1000.0 / timeDiff;
            double sendRate = (iface.getBytesSent() - prevSent) * 1000.0 / timeDiff;

            networkMetrics.setReceiveRate(round(recvRate));
            networkMetrics.setSendRate(round(sendRate));

            networkMetricsList.add(networkMetrics);

            // 更新缓存
            lastNetworkBytes[index] = iface.getBytesRecv();
            lastNetworkBytes[index + 1] = iface.getBytesSent();
            index += 2;
        }

        lastNetworkTime = currentTime;
        return networkMetricsList;
    }

    // 保留两位小数
    private double round(double value) {
        return Math.round(value * 100) / 100.0;
    }
}