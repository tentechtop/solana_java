package com.bit.solana.api;

import com.bit.solana.p2p.quic.control.GlobalFlowControl;
import com.bit.solana.p2p.quic.control.QuicFlowControl;
import com.bit.solana.p2p.quic.control.QuicCongestionControl;
import com.bit.solana.p2p.quic.QuicConnection;
import com.bit.solana.p2p.quic.QuicConnectionManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;

/**
 * QUIC监控API
 * 提供流量控制、拥塞控制和连接状态的实时监控接口
 */
@Slf4j
@RestController
@RequestMapping("/api/quic")
@CrossOrigin(origins = "*")
public class QuicMonitorApi {

    /**
     * 获取全局流量统计
     */
    @GetMapping("/global/stats")
    public Map<String, Object> getGlobalStats() {
        GlobalFlowControl globalFlowControl = GlobalFlowControl.getInstance();
        Map<String, Object> result = new HashMap<>();
        
        result.put("activeConnections", globalFlowControl.getActiveConnectionCount());
        result.put("totalConnectionsCreated", globalFlowControl.getTotalConnectionsCreated());
        result.put("totalConnectionsClosed", globalFlowControl.getTotalConnectionsClosed());
        result.put("globalBytesInFlight", globalFlowControl.getGlobalBytesInFlight());
        result.put("globalMaxInFlightBytes", globalFlowControl.getGlobalMaxInFlightBytes());
        result.put("globalUtilization", globalFlowControl.getGlobalUtilization());
        result.put("peakBytesInFlight", globalFlowControl.getPeakBytesInFlight());
        result.put("totalBytesSent", globalFlowControl.getTotalBytesSent());
        result.put("totalBytesReceived", globalFlowControl.getTotalBytesReceived());
        result.put("globalSendRate", globalFlowControl.getGlobalSendRate());
        result.put("globalReceiveRate", globalFlowControl.getGlobalReceiveRate());
        result.put("uptimeSeconds", globalFlowControl.getUptimeSeconds());
        
        return result;
    }

    /**
     * 获取所有连接的详细信息
     */
    @GetMapping("/connections")
    public List<Map<String, Object>> getAllConnections() {
        List<Map<String, Object>> connections = new ArrayList<>();
        GlobalFlowControl globalFlowControl = GlobalFlowControl.getInstance();
        
        // 获取所有流量控制连接
        for (QuicFlowControl flowControl : globalFlowControl.getAllConnections().values()) {
            Map<String, Object> connectionInfo = new HashMap<>();
            long connectionId = flowControl.getConnectionId();
            
            connectionInfo.put("connectionId", connectionId);
            connectionInfo.put("sendWindow", flowControl.getSendWindow());
            connectionInfo.put("sendWindowSize", flowControl.getSendWindowSize());
            connectionInfo.put("sendWindowUtilization", flowControl.getSendWindowUtilization());
            connectionInfo.put("receiveWindow", flowControl.getReceiveWindow());
            connectionInfo.put("receiveWindowSize", flowControl.getReceiveWindowSize());
            connectionInfo.put("receiveWindowUtilization", flowControl.getReceiveWindowUtilization());
            connectionInfo.put("bytesInFlight", flowControl.getBytesInFlight());
            connectionInfo.put("bytesReceived", flowControl.getBytesReceived());
            connectionInfo.put("totalBytesSent", flowControl.getTotalBytesSent());
            connectionInfo.put("totalBytesReceived", flowControl.getTotalBytesReceived());
            connectionInfo.put("sendRate", flowControl.getSendRate());
            connectionInfo.put("receiveRate", flowControl.getReceiveRate());
            connectionInfo.put("active", flowControl.isActive());
            connectionInfo.put("idleTime", flowControl.getIdleTime());
            
            connections.add(connectionInfo);
        }
        
        return connections;
    }

    /**
     * 获取指定连接的详细统计信息
     */
    @GetMapping("/connection/{connectionId}/stats")
    public Map<String, Object> getConnectionStats(@PathVariable Long connectionId) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            GlobalFlowControl globalFlowControl = GlobalFlowControl.getInstance();
            QuicFlowControl flowControl = globalFlowControl.getAllConnections().get(connectionId);
            
            if (flowControl != null) {
                result.put("success", true);
                result.put("connectionId", connectionId);
                result.put("flowControlStats", flowControl.getStats());
                result.put("sendWindow", flowControl.getSendWindow());
                result.put("sendWindowSize", flowControl.getSendWindowSize());
                result.put("sendWindowUtilization", flowControl.getSendWindowUtilization());
                result.put("receiveWindow", flowControl.getReceiveWindow());
                result.put("receiveWindowSize", flowControl.getReceiveWindowSize());
                result.put("receiveWindowUtilization", flowControl.getReceiveWindowUtilization());
                result.put("bytesInFlight", flowControl.getBytesInFlight());
                result.put("bytesReceived", flowControl.getBytesReceived());
                result.put("totalBytesSent", flowControl.getTotalBytesSent());
                result.put("totalBytesReceived", flowControl.getTotalBytesReceived());
                result.put("sendRate", flowControl.getSendRate());
                result.put("receiveRate", flowControl.getReceiveRate());
                result.put("active", flowControl.isActive());
                result.put("idleTime", flowControl.getIdleTime());
            } else {
                result.put("success", false);
                result.put("error", "连接不存在");
            }
        } catch (Exception e) {
            log.error("获取连接统计信息失败: connectionId={}", connectionId, e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        
        return result;
    }

    /**
     * 获取拥塞控制统计信息（如果有相关实现）
     */
    @GetMapping("/congestion/stats")
    public Map<String, Object> getCongestionStats() {
        Map<String, Object> result = new HashMap<>();
        
        // 这里可以扩展获取拥塞控制的统计信息
        // 由于拥塞控制通常与具体连接绑定，这里提供一个框架
        
        result.put("message", "拥塞控制统计需要与具体连接关联");
        result.put("note", "可以使用 /connection/{connectionId}/congestion 获取特定连接的拥塞控制信息");
        
        return result;
    }

    /**
     * 获取实时监控数据
     */
    @GetMapping("/monitor/realtime")
    public Map<String, Object> getRealtimeMonitorData() {
        Map<String, Object> result = new HashMap<>();
        GlobalFlowControl globalFlowControl = GlobalFlowControl.getInstance();
        
        // 全局统计
        result.put("global", Map.of(
            "activeConnections", globalFlowControl.getActiveConnectionCount(),
            "globalUtilization", globalFlowControl.getGlobalUtilization(),
            "totalBytesSent", globalFlowControl.getTotalBytesSent(),
            "totalBytesReceived", globalFlowControl.getTotalBytesReceived(),
            "globalSendRate", globalFlowControl.getGlobalSendRate(),
            "globalReceiveRate", globalFlowControl.getGlobalReceiveRate(),
            "uptimeSeconds", globalFlowControl.getUptimeSeconds()
        ));
        
        // 连接列表（简化版）
        List<Map<String, Object>> connections = new ArrayList<>();
        for (QuicFlowControl flowControl : globalFlowControl.getAllConnections().values()) {
            connections.add(Map.of(
                "connectionId", flowControl.getConnectionId(),
                "sendWindowUtilization", flowControl.getSendWindowUtilization(),
                "receiveWindowUtilization", flowControl.getReceiveWindowUtilization(),
                "bytesInFlight", flowControl.getBytesInFlight(),
                "sendRate", flowControl.getSendRate(),
                "receiveRate", flowControl.getReceiveRate(),
                "active", flowControl.isActive()
            ));
        }
        result.put("connections", connections);
        
        // 时间戳
        result.put("timestamp", System.currentTimeMillis());
        
        return result;
    }

    /**
     * 获取性能指标
     */
    @GetMapping("/performance/metrics")
    public Map<String, Object> getPerformanceMetrics() {
        Map<String, Object> result = new HashMap<>();
        GlobalFlowControl globalFlowControl = GlobalFlowControl.getInstance();
        
        long uptime = globalFlowControl.getUptimeSeconds();
        long totalSent = globalFlowControl.getTotalBytesSent();
        long totalReceived = globalFlowControl.getTotalBytesReceived();
        
        // 转换为MB
        double totalSentMB = totalSent / (1024.0 * 1024.0);
        double totalReceivedMB = totalReceived / (1024.0 * 1024.0);
        
        result.put("uptimeSeconds", uptime);
        result.put("uptimeHours", uptime / 3600.0);
        result.put("totalDataTransferredMB", totalSentMB + totalReceivedMB);
        result.put("totalDataSentMB", totalSentMB);
        result.put("totalDataReceivedMB", totalReceivedMB);
        result.put("averageSendRateMBps", uptime > 0 ? totalSentMB / uptime : 0);
        result.put("averageReceiveRateMBps", uptime > 0 ? totalReceivedMB / uptime : 0);
        result.put("peakThroughputMB", globalFlowControl.getPeakBytesInFlight() / (1024.0 * 1024.0));
        result.put("currentConnections", globalFlowControl.getActiveConnectionCount());
        result.put("totalConnectionsCreated", globalFlowControl.getTotalConnectionsCreated());
        result.put("connectionSuccessRate", calculateConnectionSuccessRate(globalFlowControl));
        
        return result;
    }

    /**
     * 获取系统健康状态
     */
    @GetMapping("/health")
    public Map<String, Object> getSystemHealth() {
        Map<String, Object> result = new HashMap<>();
        GlobalFlowControl globalFlowControl = GlobalFlowControl.getInstance();
        
        double utilization = globalFlowControl.getGlobalUtilization();
        int activeConnections = globalFlowControl.getActiveConnectionCount();
        
        // 健康状态评估
        String status = "HEALTHY";
        if (utilization > 90) {
            status = "WARNING";
        }
        if (utilization > 95 || activeConnections > 1000) {
            status = "CRITICAL";
        }
        
        result.put("status", status);
        result.put("globalUtilization", utilization);
        result.put("activeConnections", activeConnections);
        result.put("systemLoad", utilization / 100.0); // 标准化为0-1
        result.put("recommendations", getHealthRecommendations(utilization, activeConnections));
        result.put("timestamp", System.currentTimeMillis());
        
        return result;
    }

    /**
     * 计算连接成功率
     */
    private double calculateConnectionSuccessRate(GlobalFlowControl globalFlowControl) {
        long created = globalFlowControl.getTotalConnectionsCreated();
        long closed = globalFlowControl.getTotalConnectionsClosed();
        
        if (created == 0) return 100.0;
        
        // 这里简化计算，实际应该考虑失败连接数
        return ((double) (created - closed) / created) * 100.0;
    }

    /**
     * 获取健康建议
     */
    private List<String> getHealthRecommendations(double utilization, int activeConnections) {
        List<String> recommendations = new ArrayList<>();
        
        if (utilization > 90) {
            recommendations.add("全局流量利用率过高，建议增加带宽或减少并发连接");
        }
        if (activeConnections > 500) {
            recommendations.add("活跃连接数较多，建议考虑连接池优化");
        }
        if (utilization < 20 && activeConnections > 0) {
            recommendations.add("流量利用率较低，可能存在连接空闲问题");
        }
        if (recommendations.isEmpty()) {
            recommendations.add("系统运行正常");
        }
        
        return recommendations;
    }
}