package com.deploy.service;

import com.deploy.model.DeployConfig;
import com.deploy.util.FileUtil;
import com.deploy.util.ProcessUtil;
import com.deploy.websocket.DeployLogWebSocket;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * TongWeb部署服务实现
 */
@Service
public class TongWebDeployService {

    /**
     * 部署TongWeb服务
     * @param config 部署配置
     * @throws Exception 部署异常
     */
    public void deploy(DeployConfig config) throws Exception {
        // TongWeb 部署目录统一使用部署目录（installDir）
        String deployDir = config.getInstallDir();
        if (deployDir == null || deployDir.isEmpty()) {
            throw new IllegalArgumentException("部署目录不能为空");
        }

        DeployLogWebSocket.sendLog("开始部署TongWeb服务...");
        DeployLogWebSocket.sendLog("部署目录: " + deployDir);

        // 1. 创建部署目录
        DeployLogWebSocket.sendLog("正在创建部署目录...");
        FileUtil.createDirectory(deployDir);
        DeployLogWebSocket.sendLog("部署目录创建完成");

        // 2. 复制WAR包到部署目录
        DeployLogWebSocket.sendLog("正在复制WAR包到部署目录...");
        copyWarFiles(deployDir, config);
        DeployLogWebSocket.sendLog("WAR包复制完成");

        // 3. 启动TongWeb服务
        DeployLogWebSocket.sendLog("正在启动TongWeb服务...");
        startTongWeb(deployDir, config.getOsType());
        DeployLogWebSocket.sendLog("TongWeb服务启动命令已执行");
    }

    /**
     * 复制WAR包到部署目录
     * @param deployDir 部署目录
     * @param config 部署配置
     * @throws IOException IO异常
     */
    private void copyWarFiles(String deployDir, DeployConfig config) throws IOException {
        List<String> warFiles = config.getWarFiles();
        if (warFiles == null || warFiles.isEmpty()) {
            // 使用默认的WAR包（Java 8兼容写法）
            warFiles = Arrays.asList("tyzc.war", "gbgl.war");
        }

        for (String warFile : warFiles) {
            String sourcePath;
            String targetPath = deployDir + File.separator + warFile;
            
            if (config.getUseBuiltInWars() != null && config.getUseBuiltInWars()) {
                // 使用内置WAR包
                sourcePath = "wars/" + warFile;
                FileUtil.copyResourceToFile(sourcePath, targetPath);
                DeployLogWebSocket.sendLog("已复制WAR包: " + warFile);
            } else {
                // 使用用户选择的WAR包
                File sourceFile = new File(warFile);
                if (sourceFile.exists()) {
                    FileUtil.copyFile(warFile, targetPath);
                    DeployLogWebSocket.sendLog("已复制WAR包: " + sourceFile.getName());
                } else {
                    DeployLogWebSocket.sendLog("警告: WAR包不存在: " + warFile);
                }
            }
        }
    }

    /**
     * 启动TongWeb服务
     * @param deployDir 部署目录
     * @param osType 操作系统类型
     * @throws IOException IO异常
     */
    private void startTongWeb(String deployDir, String osType) throws IOException {
        // TongWeb的启动方式可能因版本而异
        // 这里假设TongWeb已经安装，并且可以通过命令行启动
        // 实际使用时需要根据TongWeb的具体启动方式调整
        
        String os = osType != null ? osType : System.getProperty("os.name");
        String command;
        
        if (os.toLowerCase().contains("win")) {
            // Windows系统启动命令（需要根据实际TongWeb安装路径调整）
            command = "start /B tongweb.bat";
        } else {
            // Linux系统启动命令（需要根据实际TongWeb安装路径调整）
            command = "nohup ./tongweb.sh > /dev/null 2>&1 &";
        }

        DeployLogWebSocket.sendLog("执行启动命令: " + command);
        
        // 注意：这里需要根据实际的TongWeb启动方式调整
        // 如果TongWeb需要特定的启动脚本或命令，需要在这里实现
        ProcessUtil.startProcess(command, deployDir);
        DeployLogWebSocket.sendLog("TongWeb启动进程已创建");
        
        // 注意：这里不等待进程结束，让TongWeb在后台运行
    }
}

