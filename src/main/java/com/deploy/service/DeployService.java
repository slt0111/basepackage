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
            // 说明：
            // - 该部署流程在 CompletableFuture 的线程池（commonPool）中异步执行；
            // - 线程池线程的 ContextClassLoader 可能是 AppClassLoader，导致 classpath 资源（如 tomcat/tomcat.zip、data/sql/*.sql）
            //   在 jar 部署场景下探测不到（表现为“jar 内明明有资源但扫描为 0”）；
            // - 这里显式将当前线程的 ContextClassLoader 设为 Spring Boot 的类加载器，保证异步线程可正常读取内置资源。
            Thread t = Thread.currentThread();
            ClassLoader oldCl = t.getContextClassLoader();
            ClassLoader appCl = DeployService.class.getClassLoader();
            if (appCl != null) {
                t.setContextClassLoader(appCl);
            }
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
            } finally {
                // 说明：恢复线程原有 ClassLoader，避免污染线程池内其他任务的类加载上下文。
                t.setContextClassLoader(oldCl);
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

