package com.deploy.service;

import com.deploy.util.PortUtil;
import com.deploy.websocket.DeployLogWebSocket;
import org.springframework.stereotype.Service;

/**
 * 服务检测服务
 * 检测端口是否启动成功
 */
@Service
public class ServiceCheckService {

    /**
     * 检测服务是否启动
     * @param host 主机地址
     * @param port 端口号
     * @return 检测结果消息
     */
    public String checkService(String host, int port) {
        DeployLogWebSocket.sendLog("开始检测服务: " + host + ":" + port);
        
        // 检测端口是否被占用（即服务是否启动）
        boolean isRunning = PortUtil.isPortInUse(host, port);
        
        if (isRunning) {
            String message = "服务已启动 - " + host + ":" + port;
            DeployLogWebSocket.sendLog(message);
            return message;
        } else {
            String message = "服务未启动 - " + host + ":" + port;
            DeployLogWebSocket.sendLog(message);
            return message;
        }
    }

    /**
     * 检测服务是否启动（默认localhost）
     * @param port 端口号
     * @return 检测结果消息
     */
    public String checkService(int port) {
        return checkService("localhost", port);
    }

    /**
     * 等待服务启动（带重试）
     * @param host 主机地址
     * @param port 端口号
     * @param maxRetries 最大重试次数
     * @param retryInterval 重试间隔（毫秒）
     * @return true表示服务已启动，false表示超时
     */
    public boolean waitForService(String host, int port, int maxRetries, long retryInterval) {
        DeployLogWebSocket.sendLog("等待服务启动: " + host + ":" + port);
        
        boolean started = PortUtil.checkServiceStarted(host, port, maxRetries, retryInterval);
        
        if (started) {
            DeployLogWebSocket.sendLog("服务已成功启动: " + host + ":" + port);
        } else {
            DeployLogWebSocket.sendLog("服务启动超时: " + host + ":" + port);
        }
        
        return started;
    }

    /**
     * 等待服务启动（默认参数）
     * @param port 端口号
     * @return true表示服务已启动，false表示超时
     */
    public boolean waitForService(int port) {
        return waitForService("localhost", port, 10, 1000);
    }
}

