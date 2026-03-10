package com.deploy.service;

import com.deploy.model.DeployConfig;
import com.deploy.util.FileUtil;
import com.deploy.util.ProcessUtil;
import com.deploy.util.WarConfigUtil;
import com.deploy.util.WarPathUtil;
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
        // TongWeb 部署目录统一使用部署目录（installDir），例如：
        // Linux: /usr/local/TongWeb8.0.9.09/domains/domain1/deployment
        String deployDir = config.getInstallDir();
        if (deployDir == null || deployDir.isEmpty()) {
            throw new IllegalArgumentException("部署目录不能为空");
        }

        DeployLogWebSocket.sendLog("开始部署TongWeb服务...");
        DeployLogWebSocket.sendLog("部署目录: " + deployDir);

        // 1. 尝试优雅停止已有 TongWeb 服务（避免直接覆盖部署目录导致不一致）
        stopTongWeb(deployDir, config.getOsType());

        // 2. 创建部署目录
        DeployLogWebSocket.sendLog("正在创建部署目录...");
        FileUtil.createDirectory(deployDir);
        DeployLogWebSocket.sendLog("部署目录创建完成");

        // 3. 复制WAR包到部署目录
        DeployLogWebSocket.sendLog("正在复制WAR包到部署目录...");
        copyWarFiles(deployDir, config);
        DeployLogWebSocket.sendLog("WAR包复制完成");

        // 4. 启动TongWeb服务
        DeployLogWebSocket.sendLog("正在启动TongWeb服务...");
        startTongWeb(deployDir, config.getOsType());
        DeployLogWebSocket.sendLog("TongWeb服务启动命令已执行");
    }

    /**
     * 复制WAR包到部署目录
     * 说明：优先从外部 wars 目录（项目同级 wars 文件夹）读取 WAR 包，找不到时再回退到 classpath 内置 WAR 或配置中的物理路径。
     *
     * @param deployDir 部署目录
     * @param config    部署配置
     * @throws IOException IO异常
     */
    private void copyWarFiles(String deployDir, DeployConfig config) throws IOException {
        List<String> warFiles = config.getWarFiles();
        if (warFiles == null || warFiles.isEmpty()) {
            // 使用默认的WAR包（Java 8兼容写法），保持与旧版本行为一致
            warFiles = Arrays.asList("tyzc.war", "gbgl.war");
        }

        for (String warFile : warFiles) {
            // TongWeb 场景下：直接将所有 WAR 扁平复制到参数配置的部署目录下，
            // 不再在部署目录内追加 tyzc/gbgl 等子目录，方便与现有 TongWeb 域部署路径对接。
            String fileName = warFile.contains("/") ?
                    warFile.substring(warFile.lastIndexOf("/") + 1) : warFile;
            String targetPath = deployDir + File.separator + fileName;

            // sourceWarPath：指向外部物理文件；sourceResourcePath：指向classpath 内置资源路径
            String sourceWarPath = null;
            String sourceResourcePath = null;

            if (config.getUseBuiltInWars() != null && config.getUseBuiltInWars()) {
                // 使用“内置”WAR包时，优先从项目同级 wars 目录读取
                java.nio.file.Path externalWarPath = WarPathUtil.getWarsBaseDir().resolve(warFile);
                File externalWarFile = externalWarPath.toFile();
                if (externalWarFile.exists()) {
                    sourceWarPath = externalWarFile.getAbsolutePath();
                } else {
                    // 外部目录中不存在时，回退到 JAR 内 classpath 资源
                    sourceResourcePath = "wars/" + warFile;
                }
            } else {
                // 使用用户选择的WAR包：直接将配置值视为物理路径
                File sourceFile = new File(warFile);
                if (sourceFile.exists()) {
                    sourceWarPath = sourceFile.getAbsolutePath();
                } else {
                    DeployLogWebSocket.sendLog("警告: WAR包不存在: " + warFile);
                    continue;
                }
            }

            // 针对特定 WAR 执行 YML 配置替换：与 Tomcat 分支保持一致，仅处理 tyzc-api.war 和 gbgl.war
            String lowerWar = warFile.toLowerCase();
            String appType = null;
            if (lowerWar.contains("tyzc")) {
                appType = "unified";
            } else if (lowerWar.contains("gbgl")) {
                appType = "cadre";
            }
            String ymlConfig = null;
            if (appType != null && config.getYmlConfigs() != null && !config.getYmlConfigs().isEmpty()) {
                ymlConfig = config.getYmlConfigs().get(appType);
            }
            boolean needReplaceYml = (fileName.equals("tyzc-api.war") || fileName.equals("gbgl.war"))
                    && ymlConfig != null && !ymlConfig.trim().isEmpty();

            if (needReplaceYml) {
                // 需要替换配置文件：先复制到临时 WAR，再通过 WarConfigUtil 执行 application-dev-dm.yml 替换
                DeployLogWebSocket.sendLog("检测到YML配置，正在替换TongWeb WAR包中的配置文件: " + fileName);
                File tempWarFile = File.createTempFile("tongweb_war_replace_", ".war");
                try {
                    // 复制源WAR包到临时文件：优先使用物理文件，其次回退到 classpath 资源
                    if (sourceWarPath != null) {
                        FileUtil.copyFile(sourceWarPath, tempWarFile.getAbsolutePath());
                    } else if (sourceResourcePath != null) {
                        FileUtil.copyResourceToFile(sourceResourcePath, tempWarFile.getAbsolutePath());
                    } else {
                        DeployLogWebSocket.sendLog("警告: 未找到可用的WAR源文件，跳过: " + fileName);
                        continue;
                    }

                    // 替换 WAR 包中的配置文件
                    WarConfigUtil.replaceWarConfigFileDirect(tempWarFile.getAbsolutePath(), ymlConfig, fileName);

                    // 将处理后的WAR包复制到 TongWeb 部署目录
                    FileUtil.copyFile(tempWarFile.getAbsolutePath(), targetPath);
                    DeployLogWebSocket.sendLog("已复制TongWeb WAR包（已替换配置文件）: " + fileName);
                } finally {
                    if (tempWarFile.exists()) {
                        tempWarFile.delete();
                    }
                }
            } else {
                // 不需要替换配置文件，直接复制：同样优先复制物理文件，其次回退到 classpath 资源
                if (sourceWarPath != null) {
                    FileUtil.copyFile(sourceWarPath, targetPath);
                    DeployLogWebSocket.sendLog("已复制WAR包: " + new File(sourceWarPath).getName());
                } else if (sourceResourcePath != null) {
                    FileUtil.copyResourceToFile(sourceResourcePath, targetPath);
                    DeployLogWebSocket.sendLog("已复制WAR包: " + warFile);
                } else {
                    DeployLogWebSocket.sendLog("警告: 未找到可用的WAR源文件，跳过: " + warFile);
                }
            }

            // TongWeb 部署仅复制 WAR 包即可，不需要解压
        }
    }

    /**
     * 根据部署目录推导 TongWeb 安装目录下的 bin 目录
     * 说明：针对典型目录结构 /usr/local/TongWeb8.0.9.09/domains/domain1/deployment，
     *      会回溯到 TongWeb8.0.9.09 目录并返回其 bin 子目录；若结构不符合预期则返回 null。
     *
     * @param deployDir 部署目录（通常为 domains/domain1/deployment）
     * @return TongWeb bin 目录 File 对象，可能为 null
     */
    private File resolveTongWebBinDir(String deployDir) {
        if (deployDir == null || deployDir.trim().isEmpty()) {
            return null;
        }
        File deployment = new File(deployDir).getAbsoluteFile();
        // deployment -> domain1 -> domains -> TongWebHome
        File domain1Dir = deployment.getParentFile();
        if (domain1Dir == null) {
            return null;
        }
        File domainsDir = domain1Dir.getParentFile();
        if (domainsDir == null) {
            return null;
        }
        File tongWebHome = domainsDir.getParentFile();
        if (tongWebHome == null) {
            return null;
        }
        return new File(tongWebHome, "bin");
    }

    /**
     * 停止TongWeb服务（仅在 Linux 上按典型安装路径尝试执行 stopserver.sh）
     * 说明：基于用户提供的路径约定：
     * - 部署目录：/usr/local/TongWeb8.0.9.09/domains/domain1/deployment
     * - 停止脚本：/usr/local/TongWeb8.0.9.09/bin/stopserver.sh
     *
     * @param deployDir 部署目录
     * @param osType 操作系统类型
     */
    private void stopTongWeb(String deployDir, String osType) {
        String os = osType != null ? osType : System.getProperty("os.name");
        // 当前仅在 Linux 下按约定路径尝试关闭 TongWeb，Windows 仍保持占位实现
        if (os.toLowerCase().contains("win")) {
            DeployLogWebSocket.sendLog("提示: Windows 环境下 TongWeb 停止逻辑暂未实现，请手动停止服务（若有需要）。");
            return;
        }

        File binDir = resolveTongWebBinDir(deployDir);
        if (binDir == null || !binDir.exists()) {
            DeployLogWebSocket.sendLog("提示: 未能根据部署目录推导出 TongWeb bin 目录，跳过自动停止（请确认目录结构是否为 domains/domain1/deployment）。");
            return;
        }

        File stopScript = new File(binDir, "stopserver.sh");
        if (!stopScript.exists()) {
            DeployLogWebSocket.sendLog("提示: 未找到 TongWeb 停止脚本: " + stopScript.getAbsolutePath() + "，跳过自动停止。");
            return;
        }

        try {
            // 确保脚本具有执行权限
            try {
                String chmodCmd = "chmod +x " + stopScript.getAbsolutePath();
                ProcessUtil.startProcess(chmodCmd, null);
            } catch (Exception e) {
                DeployLogWebSocket.sendLog("警告: 设置 TongWeb 停止脚本执行权限失败: " + e.getMessage());
            }

            String command = stopScript.getAbsolutePath();
            DeployLogWebSocket.sendLog("执行 TongWeb 停止命令: " + command);
            ProcessUtil.startProcess(command, binDir.getAbsolutePath());
        } catch (Exception e) {
            DeployLogWebSocket.sendLog("警告: 执行 TongWeb 停止命令时出现异常: " + e.getMessage());
        }
    }

    /**
     * 启动TongWeb服务
     * 说明：基于用户提供的路径约定：
     * - 部署目录：/usr/local/TongWeb8.0.9.09/domains/domain1/deployment
     * - 启动脚本：/usr/local/TongWeb8.0.9.09/bin/startd.sh
     *
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
            DeployLogWebSocket.sendLog("执行 TongWeb 启动命令(Windows): " + command);
            ProcessUtil.startProcess(command, deployDir);
            DeployLogWebSocket.sendLog("TongWeb启动进程已创建(Windows)。");
            return;
        }

        // Linux 场景：根据部署目录推导出 TongWeb bin 目录，并执行 startd.sh
        File binDir = resolveTongWebBinDir(deployDir);
        if (binDir == null || !binDir.exists()) {
            DeployLogWebSocket.sendLog("提示: 未能根据部署目录推导出 TongWeb bin 目录，无法自动启动 TongWeb，请检查部署目录配置。");
            return;
        }

        File startScript = new File(binDir, "startd.sh");
        if (!startScript.exists()) {
            DeployLogWebSocket.sendLog("提示: 未找到 TongWeb 启动脚本: " + startScript.getAbsolutePath() + "，无法自动启动。");
            return;
        }

        try {
            // 确保脚本具有执行权限
            try {
                String chmodCmd = "chmod +x " + startScript.getAbsolutePath();
                ProcessUtil.startProcess(chmodCmd, null);
            } catch (Exception e) {
                DeployLogWebSocket.sendLog("警告: 设置 TongWeb 启动脚本执行权限失败: " + e.getMessage());
            }

            // 直接执行绝对路径脚本，并使用 bin 目录作为工作目录
            command = "nohup " + startScript.getAbsolutePath() + " > /dev/null 2>&1 &";
            DeployLogWebSocket.sendLog("执行 TongWeb 启动命令(Linux): " + command);
            ProcessUtil.startProcess(command, binDir.getAbsolutePath());
            DeployLogWebSocket.sendLog("TongWeb启动进程已创建(Linux)，请稍后通过端口或日志确认启动状态。");
        } catch (Exception e) {
            DeployLogWebSocket.sendLog("警告: 执行 TongWeb 启动命令时出现异常: " + e.getMessage());
        }
    }
}

