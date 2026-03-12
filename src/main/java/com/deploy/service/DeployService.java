package com.deploy.service;

import com.deploy.model.DeployConfig;
import com.deploy.model.GlobalSettings;
import com.deploy.websocket.DeployLogWebSocket;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

/**
 * 部署核心服务
 * 整合Tomcat和TongWeb部署逻辑，支持场景选择
 */
@Service
public class DeployService {

    @Autowired
    private TomcatDeployService tomcatDeployService;

    @Autowired
    private TongWebDeployService tongWebDeployService;

    @Autowired
    private GlobalSettingsService globalSettingsService;

    /**
     * 开始部署（异步执行）
     * @param config 部署配置
     * @return CompletableFuture
     */
    public CompletableFuture<Void> startDeploy(DeployConfig config) {
        return CompletableFuture.runAsync(() -> {
            try {
                DeployLogWebSocket.sendLog("========== 开始部署 ==========");
                DeployLogWebSocket.sendLog("操作系统: " + config.getOsType());
                DeployLogWebSocket.sendLog("中间件类型: " + config.getMiddlewareType());
                DeployLogWebSocket.sendLog("安装目录: " + config.getInstallDir());

                // 根据中间件类型选择部署服务
                String middlewareType = config.getMiddlewareType();
                if (middlewareType == null || middlewareType.isEmpty()) {
                    // 默认中间件类型：从“全局设置”读取（若未配置则回退 Tomcat）
                    GlobalSettings settings = globalSettingsService.getSettings();
                    middlewareType = (settings != null && settings.getDefaultMiddlewareType() != null)
                            ? settings.getDefaultMiddlewareType()
                            : "Tomcat";
                }

                switch (middlewareType.toUpperCase()) {
                    case "TOMCAT":
                        tomcatDeployService.deploy(config);
                        break;
                    case "TONGWEB":
                    case "TONG_WEB":
                        tongWebDeployService.deploy(config);
                        break;
                    default:
                        throw new IllegalArgumentException("不支持的中间件类型: " + middlewareType);
                }

                DeployLogWebSocket.sendLog("========== 部署完成 ==========");
            } catch (Exception e) {
                DeployLogWebSocket.sendLog("部署失败: " + e.getMessage());
                e.printStackTrace();
                // 将异常信息也发送到WebSocket
                DeployLogWebSocket.sendLog("异常堆栈: " + getStackTrace(e));
            }
        });
    }

    /**
     * 获取异常堆栈信息
     */
    private String getStackTrace(Exception e) {
        java.io.StringWriter sw = new java.io.StringWriter();
        java.io.PrintWriter pw = new java.io.PrintWriter(sw);
        e.printStackTrace(pw);
        return sw.toString();
    }
}

