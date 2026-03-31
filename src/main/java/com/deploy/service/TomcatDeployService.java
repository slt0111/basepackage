package com.deploy.service;

import com.deploy.model.DeployConfig;
import com.deploy.model.PortConfig;
import com.deploy.util.FileUtil;
import com.deploy.util.PortUtil;
import com.deploy.util.ProcessUtil;
import com.deploy.util.WarConfigUtil;
import com.deploy.util.WarPathUtil;
import com.deploy.websocket.DeployLogWebSocket;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;
import java.util.regex.Matcher;

/**
 * Tomcat部署服务实现
 */
@Service
public class TomcatDeployService {

    /**
     * 部署Tomcat服务
     * 统一支撑和干部应用分别部署到独立的Tomcat实例
     * @param config 部署配置
     * @throws Exception 部署异常
     */
    public void deploy(DeployConfig config) throws Exception {
        String installDir = config.getInstallDir();
        DeployLogWebSocket.sendLog("开始部署Tomcat服务...");
        DeployLogWebSocket.sendLog("安装目录: " + installDir);

        // 1. 部署统一支撑应用（复制所有tyzc相关的WAR包）
        DeployLogWebSocket.sendLog("========== 开始部署统一支撑应用 ==========");
        deployApplication(installDir, "tyzc", "tomcat-tyzc", "unified", config);
        
        // 2. 部署干部管理应用（复制所有gbgl相关的WAR包）
        DeployLogWebSocket.sendLog("========== 开始部署干部管理应用 ==========");
        deployApplication(installDir, "gbgl", "tomcat-gbgl", "cadre", config);
        
        DeployLogWebSocket.sendLog("========== 所有应用部署完成 ==========");
    }

    /**
     * 部署单个应用
     * @param installDir 安装目录
     * @param appName 应用名称（用于日志，tyzc或gbgl）
     * @param tomcatDirName Tomcat目录名（tomcat-tyzc或tomcat-gbgl）
     * @param appType 应用类型（unified或cadre）
     * @param config 部署配置
     * @throws Exception 部署异常
     */
    private void deployApplication(String installDir, String appName, String tomcatDirName, 
                                   String appType, DeployConfig config) throws Exception {
        DeployLogWebSocket.sendLog("开始部署" + appName + "应用...");
        
        // 1. 获取或解压Tomcat目录
        String tomcatDir = extractTomcat(installDir, tomcatDirName);

        // 1.1 在 Windows 上为当前 Tomcat 实例写入专用 JAVA_HOME（若配置了 tomcatJdkHome）
        // 说明：部分目标环境要求 Tomcat 必须运行在 JDK 17 上，但本部署工具自身可能运行在 JDK 8，
        //       因此通过在 setclasspath.bat 中设置 JAVA_HOME=tomcatJdkHome 的方式为 Tomcat 指定独立的 JDK。
        configureTomcatJavaHome(tomcatDir, config);
        
        // 2. 获取该应用对应的端口配置
        List<PortConfig> appPorts = getPortsForApplication(appType, config.getPorts());
        
        // 3. 检测并关闭已运行的Tomcat服务
        DeployLogWebSocket.sendLog("正在检测" + appName + " Tomcat服务状态...");
        stopTomcatIfRunning(tomcatDir, config.getOsType(), appPorts);
        
        // 4. 复制所有相关的WAR包到webapps目录（如果需要替换yml，会先替换）
        DeployLogWebSocket.sendLog("正在复制" + appName + " WAR包...");
        copyWarFiles(tomcatDir, appName, appType, config);
        DeployLogWebSocket.sendLog("WAR包复制完成");

        // 5. 修改server.xml端口配置
        DeployLogWebSocket.sendLog("正在修改" + appName + " server.xml端口配置...");
        modifyServerXml(tomcatDir, appPorts);
        DeployLogWebSocket.sendLog("server.xml配置修改完成");

        // 6. 启动Tomcat服务
        DeployLogWebSocket.sendLog("正在启动" + appName + " Tomcat服务...");
        startTomcat(tomcatDir, config.getOsType(), appPorts);
    }

    /**
     * 为 Tomcat 写入专用 JAVA_HOME（仅在 Windows 环境生效）
     * @param tomcatDir Tomcat 根目录
     * @param config    部署配置（包含 tomcatJdkHome 与 osType）
     */
    private void configureTomcatJavaHome(String tomcatDir, DeployConfig config) {
        // 1. 仅在 Windows 环境下处理 setclasspath.bat
        String os = config.getOsType() != null ? config.getOsType() : System.getProperty("os.name");
        if (os == null || !os.toLowerCase().contains("win")) {
            return;
        }

        // 2. 读取前端传入的专用 JDK 安装目录
        String jdkHome = config.getTomcatJdkHome();
        if (jdkHome == null || jdkHome.trim().isEmpty()) {
            // 未显式配置时，不强制覆盖 setclasspath.bat，仅记录提示日志
            DeployLogWebSocket.sendLog("提示: 未配置 Tomcat 专用 JDK 路径(tomcatJdkHome)，将使用系统默认 JAVA_HOME 启动 Tomcat。");
            return;
        }

        File jdkHomeDir = new File(jdkHome.trim());
        String normalizedJdkHome = jdkHomeDir.getAbsolutePath();

        // 3. 定位 setclasspath.bat
        File setClasspathFile = new File(tomcatDir + File.separator + "bin" + File.separator + "setclasspath.bat");
        if (!setClasspathFile.exists()) {
            DeployLogWebSocket.sendLog("警告: 未找到 Tomcat setclasspath.bat，无法写入 JAVA_HOME: " + setClasspathFile.getAbsolutePath());
            return;
        }

        try {
            String path = setClasspathFile.getAbsolutePath();
            String content = FileUtil.readFileContent(path);

            // 4. 将 set JAVA_HOME 行替换/插入到脚本中
            // 说明：
            // - 如果已有 set JAVA_HOME=XXX，则直接整行替换为新的目录
            // - 如果没有，则在 @echo off 之后插入一行 set JAVA_HOME=...
            // 注意：
            // - 使用 replaceAll 时，replacement 字符串中的反斜杠会被当作转义字符，
            //   若不做处理，C:\Program Files\Java\jdk-17 会被“吃掉反斜杠”变成 C:Program FilesJavajdk-17，
            //   导致 Tomcat 日志中出现你看到的路径问题；
            // - 这里通过 Matcher.quoteReplacement 进行转义，保证写入脚本的路径保持原样。
            String newContent;
            if (content.matches("(?is).*^\\s*set\\s+JAVA_HOME=.*$.*")) {
                // 使用正则替换已有的 JAVA_HOME 设置（replacement 需使用 quoteReplacement 防止反斜杠丢失）
                String replacementLine = "set JAVA_HOME=" + normalizedJdkHome;
                newContent = content.replaceAll("(?im)^\\s*set\\s+JAVA_HOME=.*$",
                        Matcher.quoteReplacement(replacementLine));
            } else if (content.toLowerCase().contains("@echo off")) {
                // 在 @echo off 后插入一行 set JAVA_HOME=...，同样需要对整个 replacement 做 quoteReplacement
                String replacementBlock = "@echo off" + System.lineSeparator()
                        + "set JAVA_HOME=" + normalizedJdkHome + System.lineSeparator();
                newContent = content.replaceFirst("(?i)@echo off\\s*",
                        Matcher.quoteReplacement(replacementBlock));
            } else {
                // 未找到 @echo off，则直接在文件开头追加设置行
                newContent = "set JAVA_HOME=" + normalizedJdkHome + System.lineSeparator() + content;
            }

            FileUtil.writeFileContent(path, newContent);
            DeployLogWebSocket.sendLog("已在 Tomcat setclasspath.bat 中设置 JAVA_HOME=" + normalizedJdkHome);
        } catch (Exception e) {
            // 出现异常时记录警告，但不阻断整体部署流程
            DeployLogWebSocket.sendLog("警告: 写入 Tomcat setclasspath.bat 中的 JAVA_HOME 失败: " + e.getMessage());
        }
    }

    /**
     * 根据应用类型获取对应的端口配置
     * 如果没有配置Shutdown和AJP端口，会根据HTTP端口自动计算，确保两个Tomcat实例端口不冲突
     * @param appType 应用类型（unified或cadre）
     * @param allPorts 所有端口配置
     * @return 该应用对应的端口配置列表（包含HTTP、Shutdown、AJP端口）
     */
    private List<PortConfig> getPortsForApplication(String appType, List<PortConfig> allPorts) {
        List<PortConfig> appPorts = new java.util.ArrayList<>();
        
        String portNamePrefix;
        if ("unified".equals(appType)) {
            portNamePrefix = "统一支撑";
        } else {
            portNamePrefix = "干部应用";
        }
        
        // 从配置中获取该应用的端口
        Integer httpPort = null;
        Integer httpsPort = null;
        Integer shutdownPort = null;
        Integer ajpPort = null;
        
        if (allPorts != null && !allPorts.isEmpty()) {
            for (PortConfig portConfig : allPorts) {
                if (portConfig.getName() != null && portConfig.getName().contains(portNamePrefix)) {
                    String type = portConfig.getType();
                    Integer port = portConfig.getPort();
                    
                    if (port != null) {
                        if ("HTTP".equalsIgnoreCase(type) || "CONNECTOR".equalsIgnoreCase(type)) {
                            httpPort = port;
                            appPorts.add(portConfig);
                        } else if ("HTTPS".equalsIgnoreCase(type) || "SSL".equalsIgnoreCase(type)) {
                            httpsPort = port;
                            appPorts.add(portConfig);
                        } else if ("SHUTDOWN".equalsIgnoreCase(type)) {
                            shutdownPort = port;
                            appPorts.add(portConfig);
                        } else if ("AJP".equalsIgnoreCase(type)) {
                            ajpPort = port;
                            appPorts.add(portConfig);
                        }
                    }
                }
            }
        }
        
        // 如果没有配置HTTP端口，使用默认值
        if (httpPort == null) {
            if ("unified".equals(appType)) {
                httpPort = 8111; // 统一支撑默认端口
            } else {
                httpPort = 8222; // 干部应用默认端口
            }
            PortConfig httpConfig = new PortConfig();
            httpConfig.setName(portNamePrefix + "HTTP端口");
            httpConfig.setType("HTTP");
            httpConfig.setPort(httpPort);
            appPorts.add(httpConfig);
            DeployLogWebSocket.sendLog("使用默认HTTP端口: " + httpPort);
        }
        
        // 如果没有配置Shutdown端口，根据HTTP端口自动计算（避免冲突）
        if (shutdownPort == null) {
            // Shutdown端口 = HTTP端口 - 3（统一支撑：8085，干部应用：8096）
            shutdownPort = httpPort - 3;
            PortConfig shutdownConfig = new PortConfig();
            shutdownConfig.setName(portNamePrefix + "Shutdown端口");
            shutdownConfig.setType("SHUTDOWN");
            shutdownConfig.setPort(shutdownPort);
            appPorts.add(shutdownConfig);
            DeployLogWebSocket.sendLog("自动计算Shutdown端口: " + shutdownPort);
        }
        
        // 如果没有配置AJP端口，根据HTTP端口自动计算（避免冲突）
        if (ajpPort == null) {
            // AJP端口 = HTTP端口 - 1（统一支撑：8087，干部应用：8098）
            ajpPort = httpPort - 1;
            PortConfig ajpConfig = new PortConfig();
            ajpConfig.setName(portNamePrefix + "AJP端口");
            ajpConfig.setType("AJP");
            ajpConfig.setPort(ajpPort);
            appPorts.add(ajpConfig);
            DeployLogWebSocket.sendLog("自动计算AJP端口: " + ajpPort);
        }
        
        // 如果没有配置HTTPS端口，根据HTTP端口自动计算（避免冲突）
        if (httpsPort == null) {
            // HTTPS端口 = HTTP端口 + 355（统一支撑：8443，干部应用：8454）
            // 或者使用 HTTP端口 + 355 来避免冲突
            httpsPort = httpPort + 355;
            PortConfig httpsConfig = new PortConfig();
            httpsConfig.setName(portNamePrefix + "HTTPS端口");
            httpsConfig.setType("HTTPS");
            httpsConfig.setPort(httpsPort);
            appPorts.add(httpsConfig);
            DeployLogWebSocket.sendLog("自动计算HTTPS端口: " + httpsPort);
        }
        
        return appPorts;
    }

    /**
     * 获取或解压Tomcat目录
     * 如果Tomcat目录已存在，直接使用；否则解压Tomcat压缩包
     * @param installDir 安装目录
     * @param tomcatDirName Tomcat目录名（tomcat-tyzc或tomcat-gbgl）
     * @return Tomcat目录路径
     * @throws IOException IO异常
     */
    private String extractTomcat(String installDir, String tomcatDirName) throws IOException {
        // 创建安装目录
        FileUtil.createDirectory(installDir);

        // 解压到指定目录名
        String extractDir = installDir + File.separator + tomcatDirName;
        File extractDirFile = new File(extractDir);
        
        // 如果Tomcat目录已存在，直接使用，不重新解压
        if (extractDirFile.exists() && extractDirFile.isDirectory()) {
            DeployLogWebSocket.sendLog("检测到已存在的Tomcat目录，直接使用: " + extractDir);
            // 查找已存在的Tomcat目录（可能有一层目录）
            File[] files = extractDirFile.listFiles();
            if (files != null && files.length == 1 && files[0].isDirectory()) {
                // 如果只有一个子目录，使用该目录作为Tomcat目录
                return files[0].getAbsolutePath();
            }
            return extractDir;
        }

        // Tomcat目录不存在，需要解压
        DeployLogWebSocket.sendLog("正在解压Tomcat压缩包到: " + tomcatDirName);

        // 查找Tomcat压缩包（支持zip和tar.gz）
        String[] possibleNames = {"tomcat.zip", "tomcat.tar.gz", "apache-tomcat.zip"};
        String tomcatResource = null;
        
        for (String name : possibleNames) {
            // 说明：Tomcat 压缩包属于二进制资源（zip/tar.gz），这里只做存在性探测，不做文本读取，避免误判为“不存在”。
            if (FileUtil.resourceExists("tomcat/" + name)) {
                tomcatResource = "tomcat/" + name;
                break;
            }
        }

        if (tomcatResource == null) {
            // 说明：补充诊断信息，便于在 jar 部署环境直接定位 classpath 是否包含 tomcat.zip
            throw new IOException("未找到Tomcat压缩包资源文件" + buildTomcatResourceDiagnostics(possibleNames));
        }

        // 解压Tomcat压缩包
        if (tomcatResource.endsWith(".zip")) {
            FileUtil.unzipResource(tomcatResource, extractDir);
        } else {
            // 对于tar.gz，需要特殊处理，这里先按zip处理
            // 实际项目中可以使用Apache Commons Compress的TarArchiveInputStream
            throw new IOException("暂不支持tar.gz格式，请使用zip格式");
        }

        DeployLogWebSocket.sendLog("Tomcat解压完成: " + extractDir);

        // 查找解压后的Tomcat目录（可能有一层目录）
        extractDirFile = new File(extractDir);
        File[] files = extractDirFile.listFiles();
        if (files != null && files.length == 1 && files[0].isDirectory()) {
            // 如果只有一个子目录，使用该目录作为Tomcat目录
            return files[0].getAbsolutePath();
        }
        
        return extractDir;
    }

    /**
     * 构造 Tomcat 资源探测诊断信息
     * 说明：避免现场“jar 里明明有资源但程序探测不到”时无从下手。
     */
    private String buildTomcatResourceDiagnostics(String[] possibleNames) {
        StringBuilder sb = new StringBuilder();
        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            sb.append("。诊断信息: ");
            sb.append("thread=").append(Thread.currentThread().getName());
            sb.append(", cl=").append(cl != null ? cl.getClass().getName() : "null");
            sb.append(", user.dir=").append(System.getProperty("user.dir"));

            // 1) 对每个候选名称做 resourceExists 探测结果输出
            if (possibleNames != null && possibleNames.length > 0) {
                sb.append(", resourceExists=[");
                for (int i = 0; i < possibleNames.length; i++) {
                    String name = possibleNames[i];
                    if (i > 0) sb.append(" | ");
                    sb.append("tomcat/").append(name).append("=").append(FileUtil.resourceExists("tomcat/" + name));
                }
                sb.append("]");
            }

            // 2) 资源扫描：列出 classpath*:tomcat/* 实际能扫描到的资源 URL（只取前 10 个避免刷屏）
            ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver(cl);
            Resource[] rs = resolver.getResources("classpath*:tomcat/*");
            sb.append(", scanCount=").append(rs != null ? rs.length : 0);
            if (rs != null && rs.length > 0) {
                sb.append(", scan=[");
                int limit = Math.min(10, rs.length);
                for (int i = 0; i < limit; i++) {
                    Resource r = rs[i];
                    if (i > 0) sb.append(" | ");
                    try {
                        sb.append(r.getURL().toString());
                    } catch (Exception e) {
                        sb.append(String.valueOf(r));
                    }
                }
                if (rs.length > limit) {
                    sb.append(" | ...more");
                }
                sb.append("]");
            }
        } catch (Exception ignored) {
            // ignored
        }
        return sb.toString();
    }

    /**
     * 复制所有相关的WAR包到webapps目录
     * 如果配置了yml文件，会先替换WAR包中的配置文件（只替换tyzc-api.war和gbgl.war）
     * 说明：优先从外部 wars 目录（项目同级 wars 文件夹）读取 WAR 包，找不到时再回退到 classpath 内置 WAR，兼容老版本打包资源。
     *
     * @param tomcatDir Tomcat目录
     * @param appName   应用名称（tyzc或gbgl）
     * @param appType   应用类型（unified或cadre）
     * @param config    部署配置
     * @throws IOException IO异常
     */
    private void copyWarFiles(String tomcatDir, String appName, String appType, DeployConfig config) throws IOException {
        String webappsDir = tomcatDir + File.separator + "webapps";
        FileUtil.createDirectory(webappsDir);

        // 获取所有WAR包配置
        List<String> allWarFiles = config.getWarFiles();
        if (allWarFiles == null || allWarFiles.isEmpty()) {
            DeployLogWebSocket.sendLog("警告: 未配置WAR包列表");
            return;
        }
        
        // 筛选出该应用相关的WAR包
        // tyzc应用：包含"tyzc"的WAR包
        // gbgl应用：包含"gbgl"的WAR包
        List<String> appWarFiles = new java.util.ArrayList<>();
        for (String warFile : allWarFiles) {
            String fileName = warFile.contains("/") ? 
                    warFile.substring(warFile.lastIndexOf("/") + 1) : warFile;
            String lowerFileName = fileName.toLowerCase();
            String lowerAppName = appName.toLowerCase();
            
            // 检查WAR包是否属于该应用
            if (lowerFileName.contains(lowerAppName) || 
                (lowerAppName.equals("tyzc") && lowerFileName.contains("tyzc")) ||
                (lowerAppName.equals("gbgl") && lowerFileName.contains("gbgl"))) {
                appWarFiles.add(warFile);
            }
        }
        
        if (appWarFiles.isEmpty()) {
            DeployLogWebSocket.sendLog("警告: 未找到" + appName + "相关的WAR包");
            return;
        }
        
        DeployLogWebSocket.sendLog("找到" + appWarFiles.size() + "个" + appName + "相关的WAR包");
        
        // 获取YML配置（用于替换tyzc-api.war和gbgl.war）
        String ymlConfig = null;
        if (config.getYmlConfigs() != null && !config.getYmlConfigs().isEmpty()) {
            ymlConfig = config.getYmlConfigs().get(appType);
        }
        
        // 遍历复制每个WAR包
        for (String warFile : appWarFiles) {
            String fileName = warFile.contains("/") ?
                    warFile.substring(warFile.lastIndexOf("/") + 1) : warFile;

            // sourceWarPath：指向外部物理文件；sourceResourcePath：指向classpath 内置资源路径
            String sourceWarPath = null;
            String sourceResourcePath = null;

            // 查找源WAR包路径：优先使用外部 wars 目录，其次回退到 classpath 资源，最后才使用配置中的绝对/相对路径
            if (config.getUseBuiltInWars() != null && config.getUseBuiltInWars()) {
                // 使用“内置”WAR包时，优先从项目同级 wars 目录读取（支持运维在外部直接替换 WAR 文件）
                java.nio.file.Path externalWarPath = WarPathUtil.getWarsBaseDir().resolve(warFile);
                File externalWarFile = externalWarPath.toFile();
                if (externalWarFile.exists()) {
                    sourceWarPath = externalWarFile.getAbsolutePath();
                } else {
                    // 外部目录没有对应文件时，回退到打包在 JAR 内的 classpath 资源
                    sourceResourcePath = "wars/" + warFile;
                }
            } else {
                // 使用用户选择的WAR包时，直接把配置值当作物理路径处理
                File sourceFile = new File(warFile);
                if (sourceFile.exists()) {
                    sourceWarPath = sourceFile.getAbsolutePath();
                } else {
                    DeployLogWebSocket.sendLog("警告: WAR包不存在: " + warFile);
                    continue;
                }
            }

            // 目标WAR包路径
            String targetPath = webappsDir + File.separator + fileName;
            File targetFile = new File(targetPath);
            
            // 如果WAR包已存在，先删除以进行重部署
            if (targetFile.exists()) {
                DeployLogWebSocket.sendLog("检测到已存在的WAR包: " + fileName + "，正在删除以进行重部署...");
                if (targetFile.delete()) {
                    DeployLogWebSocket.sendLog("已删除旧的WAR包: " + fileName);
                } else {
                    DeployLogWebSocket.sendLog("警告: 无法删除旧的WAR包: " + fileName);
                }
            }
            
            // 检查是否需要替换配置文件（只替换tyzc-api.war和gbgl.war）
            boolean needReplaceYml = (fileName.equals("tyzc-api.war") || fileName.equals("gbgl.war"))
                    && ymlConfig != null && !ymlConfig.trim().isEmpty();
            
            if (needReplaceYml) {
                // 需要替换配置文件
                DeployLogWebSocket.sendLog("检测到YML配置，正在替换WAR包中的配置文件: " + fileName);

                // 创建临时文件用于处理WAR包
                File tempWarFile = File.createTempFile("war_replace_", ".war");

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

                    // 替换WAR包中的配置文件（直接替换，不重新打包）
                    WarConfigUtil.replaceWarConfigFileDirect(tempWarFile.getAbsolutePath(), ymlConfig, fileName);

                    // 将处理后的WAR包复制到目标目录
                    FileUtil.copyFile(tempWarFile.getAbsolutePath(), targetPath);
                    DeployLogWebSocket.sendLog("已复制WAR包（已替换配置文件）: " + fileName);
                } finally {
                    // 清理临时文件
                    if (tempWarFile.exists()) {
                        tempWarFile.delete();
                    }
                }
            } else {
                // 不需要替换配置文件，直接复制：同样优先使用外部物理文件，其次回退到 classpath 资源
                if (sourceWarPath != null) {
                    FileUtil.copyFile(sourceWarPath, targetPath);
                    DeployLogWebSocket.sendLog("已复制WAR包: " + fileName);
                } else if (sourceResourcePath != null) {
                    FileUtil.copyResourceToFile(sourceResourcePath, targetPath);
                    DeployLogWebSocket.sendLog("已复制WAR包: " + fileName);
                } else {
                    DeployLogWebSocket.sendLog("警告: 未找到可用的WAR源文件，跳过: " + fileName);
                }
            }

            // 拷贝到部署目录后解压 WAR 包，解压到与 WAR 同名的目录（如 tyzc-api.war -> webapps/tyzc-api/）
            explodeWarToDir(webappsDir, fileName, targetPath);
        }
    }

    /**
     * 将已复制到部署目录的 WAR 包解压到同名目录（如 tyzc-api.war 解压为 tyzc-api/）
     * 若已存在同名解压目录则先删除再解压，保证为最新内容。
     *
     * @param webappsDir webapps 目录路径
     * @param fileName   WAR 文件名
     * @param targetPath WAR 文件完整路径
     */
    private void explodeWarToDir(String webappsDir, String fileName, String targetPath) throws IOException {
        if (fileName == null || !fileName.endsWith(".war")) {
            return;
        }
        String baseName = fileName.substring(0, fileName.length() - 4);
        String explodedDir = webappsDir + File.separator + baseName;
        File explodedDirFile = new File(explodedDir);
        if (explodedDirFile.exists()) {
            DeployLogWebSocket.sendLog("检测到已存在的解压目录: " + baseName + "，正在删除以进行重部署...");
            FileUtil.deleteFile(explodedDir);
        }
        FileUtil.unzipFile(targetPath, explodedDir);
        DeployLogWebSocket.sendLog("已解压WAR包至: " + explodedDir);
    }

    /**
     * 修改server.xml端口配置
     * 无论是否已存在，都根据最新配置重新修改端口
     * @param tomcatDir Tomcat目录
     * @param ports 端口配置列表
     * @throws Exception 异常
     */
    private void modifyServerXml(String tomcatDir, List<PortConfig> ports) throws Exception {
        String serverXmlPath = tomcatDir + File.separator + "conf" + File.separator + "server.xml";
        File serverXmlFile = new File(serverXmlPath);
        
        if (!serverXmlFile.exists()) {
            throw new IOException("server.xml文件不存在: " + serverXmlPath);
        }

        // 读取XML文件
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(serverXmlFile);

        // 如果配置了端口，则根据最新配置修改端口
        if (ports != null && !ports.isEmpty()) {
            DeployLogWebSocket.sendLog("正在根据最新配置修改server.xml端口...");
            
            // 先修改Shutdown端口（Server端口）
            for (PortConfig portConfig : ports) {
                String portType = portConfig.getType();
                Integer port = portConfig.getPort();

                if (portType != null && port != null && "SHUTDOWN".equalsIgnoreCase(portType)) {
                    modifyServerPort(doc, port);
                    break;
                }
            }
            
            // 再修改连接器端口（HTTP和AJP）
        for (PortConfig portConfig : ports) {
            String portType = portConfig.getType();
            Integer port = portConfig.getPort();

            if (portType == null || port == null) {
                continue;
            }

            // 根据端口类型修改对应的端口
            switch (portType.toUpperCase()) {
                case "HTTP":
                case "CONNECTOR":
                    // 修改HTTP连接器端口
                        modifyConnectorPort(doc, "HTTP/1.1", port, null);
                        break;
                    case "HTTPS":
                    case "SSL":
                        // 修改HTTPS连接器端口
                        modifyConnectorPort(doc, "HTTPS", port, "https");
                        break;
                case "AJP":
                    // 修改AJP连接器端口
                        modifyConnectorPort(doc, "AJP/1.3", port, null);
                    break;
                case "SHUTDOWN":
                        // Shutdown端口已在上面处理，这里跳过
                    break;
                }
            }
            
            DeployLogWebSocket.sendLog("所有端口配置已更新完成");
        } else {
            DeployLogWebSocket.sendLog("未配置端口，使用默认端口");
        }

        // 保存修改后的XML文件（无论是否修改了端口，都保存以确保配置是最新的）
        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        Transformer transformer = transformerFactory.newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        
        DOMSource source = new DOMSource(doc);
        StreamResult result = new StreamResult(serverXmlFile);
        transformer.transform(source, result);

        DeployLogWebSocket.sendLog("server.xml端口配置已更新");
    }

    /**
     * 修改连接器端口
     * @param doc XML文档
     * @param protocol 协议类型（HTTP/1.1、AJP/1.3 或 HTTPS）
     * @param port 端口号
     * @param scheme 协议方案（"https"用于HTTPS，null用于HTTP/AJP）
     */
    private void modifyConnectorPort(Document doc, String protocol, int port, String scheme) {
        NodeList connectors = doc.getElementsByTagName("Connector");
        boolean found = false;
        
        for (int i = 0; i < connectors.getLength(); i++) {
            Element connector = (Element) connectors.item(i);
            String connectorProtocol = connector.getAttribute("protocol");
            String connectorScheme = connector.getAttribute("scheme");
            String connectorPort = connector.getAttribute("port");
            
            boolean match = false;
            
            // 对于HTTPS，通过scheme="https"或端口8443来匹配
            if ("HTTPS".equalsIgnoreCase(protocol) || (scheme != null && "https".equalsIgnoreCase(scheme))) {
                if ((connectorScheme != null && "https".equalsIgnoreCase(connectorScheme)) ||
                    (connectorPort != null && "8443".equals(connectorPort)) ||
                    (connectorProtocol != null && (connectorProtocol.contains("HTTP") || connectorProtocol.contains("Http11")) 
                     && connectorScheme != null && "https".equalsIgnoreCase(connectorScheme))) {
                    match = true;
                }
            }
            // 对于HTTP，匹配protocol包含HTTP/1.1且scheme不是https的连接器
            else if ("HTTP/1.1".equals(protocol) || "HTTP".equalsIgnoreCase(protocol)) {
                if (connectorProtocol != null && connectorProtocol.contains("HTTP")) {
                    // 排除HTTPS连接器
                    if (connectorScheme == null || !"https".equalsIgnoreCase(connectorScheme)) {
                        match = true;
                    }
                }
            }
            // 对于AJP，匹配protocol包含AJP/1.3的连接器
            else if ("AJP/1.3".equals(protocol) || "AJP".equalsIgnoreCase(protocol)) {
                if (connectorProtocol != null && connectorProtocol.contains("AJP")) {
                    match = true;
                }
            }
            
            if (match) {
                String oldPort = connector.getAttribute("port");
                connector.setAttribute("port", String.valueOf(port));
                DeployLogWebSocket.sendLog("已修改" + protocol + "端口: " + oldPort + " -> " + port);
                found = true;
                // 不return，继续检查是否有多个相同协议的连接器
            }
        }
        
        if (!found) {
            DeployLogWebSocket.sendLog("警告: 未找到" + protocol + "连接器，无法修改端口");
        }
    }

    /**
     * 修改Server端口（Shutdown端口）
     * @param doc XML文档
     * @param port 端口号
     */
    private void modifyServerPort(Document doc, int port) {
        NodeList servers = doc.getElementsByTagName("Server");
        if (servers.getLength() > 0) {
            Element server = (Element) servers.item(0);
            String oldPort = server.getAttribute("port");
            server.setAttribute("port", String.valueOf(port));
            DeployLogWebSocket.sendLog("已修改Shutdown端口: " + oldPort + " -> " + port);
        } else {
            DeployLogWebSocket.sendLog("警告: 未找到Server元素，无法修改Shutdown端口");
        }
    }

    /**
     * 检测并关闭已运行的Tomcat服务
     * @param tomcatDir Tomcat目录
     * @param osType 操作系统类型
     * @param ports 端口配置列表
     * @throws Exception 异常
     */
    private void stopTomcatIfRunning(String tomcatDir, String osType, List<PortConfig> ports) throws Exception {
        String os = osType != null ? osType : System.getProperty("os.name");
        
        // 首先尝试使用shutdown脚本关闭
        String shutdownScriptPath;
        if (os.toLowerCase().contains("win")) {
            shutdownScriptPath = tomcatDir + File.separator + "bin" + File.separator + "shutdown.bat";
        } else {
            shutdownScriptPath = tomcatDir + File.separator + "bin" + File.separator + "shutdown.sh";
        }
        
        File shutdownScript = new File(shutdownScriptPath);
        boolean serviceRunning = false;
        
        // 检测服务是否运行（通过端口检测）
        if (ports != null && !ports.isEmpty()) {
            for (PortConfig portConfig : ports) {
                String portType = portConfig.getType();
                Integer port = portConfig.getPort();
                
                if (portType != null && port != null) {
                    // 检测HTTP端口或SHUTDOWN端口
                    if ("HTTP".equalsIgnoreCase(portType) || "CONNECTOR".equalsIgnoreCase(portType)) {
                        if (PortUtil.isPortInUse(port)) {
                            serviceRunning = true;
                            DeployLogWebSocket.sendLog("检测到Tomcat服务正在运行（端口: " + port + "）");
                            break;
                        }
                    }
                }
            }
        } else {
            // 如果没有配置端口，尝试检测默认端口8080
            if (PortUtil.isPortInUse(8080)) {
                serviceRunning = true;
                DeployLogWebSocket.sendLog("检测到Tomcat服务正在运行（默认端口: 8080）");
            }
        }
        
        // 如果服务未运行，直接返回
        if (!serviceRunning) {
            DeployLogWebSocket.sendLog("Tomcat服务未运行，无需关闭");
            return;
        }
        
        // 服务正在运行，尝试关闭
        DeployLogWebSocket.sendLog("正在关闭Tomcat服务...");
        
        // 方法1: 使用shutdown脚本关闭
        if (shutdownScript.exists()) {
            try {
                DeployLogWebSocket.sendLog("使用shutdown脚本关闭服务...");
                if (!os.toLowerCase().contains("win")) {
                    // Linux系统确保脚本有执行权限
                    try {
                        Process chmodProcess = ProcessUtil.startProcess("chmod +x " + shutdownScriptPath, null);
                        chmodProcess.waitFor();
                    } catch (Exception e) {
                        DeployLogWebSocket.sendLog("警告: 无法设置脚本执行权限: " + e.getMessage());
                    }
                }
                
                Process shutdownProcess = ProcessUtil.startProcess(shutdownScriptPath, tomcatDir);
                // 等待shutdown脚本执行完成（最多等待10秒）
                boolean finished = ProcessUtil.waitForProcess(shutdownProcess, 10000);
                if (finished) {
                    DeployLogWebSocket.sendLog("shutdown脚本执行完成");
                } else {
                    DeployLogWebSocket.sendLog("警告: shutdown脚本执行超时");
                }
                
                // 等待服务关闭（最多等待15秒）
                DeployLogWebSocket.sendLog("等待服务关闭...");
                for (int i = 0; i < 15; i++) {
                    boolean stillRunning = false;
                    if (ports != null && !ports.isEmpty()) {
                        for (PortConfig portConfig : ports) {
                            String portType = portConfig.getType();
                            Integer port = portConfig.getPort();
                            if (portType != null && port != null && 
                                ("HTTP".equalsIgnoreCase(portType) || "CONNECTOR".equalsIgnoreCase(portType))) {
                                if (PortUtil.isPortInUse(port)) {
                                    stillRunning = true;
                                    break;
                                }
                            }
                        }
                    } else {
                        stillRunning = PortUtil.isPortInUse(8080);
                    }
                    
                    if (!stillRunning) {
                        DeployLogWebSocket.sendLog("Tomcat服务已成功关闭");
                        return;
                    }
                    Thread.sleep(1000);
                }
                
                DeployLogWebSocket.sendLog("警告: 等待超时，服务可能仍在运行");
            } catch (Exception e) {
                DeployLogWebSocket.sendLog("警告: 使用shutdown脚本关闭失败: " + e.getMessage());
            }
        } else {
            DeployLogWebSocket.sendLog("警告: shutdown脚本不存在: " + shutdownScriptPath);
        }
        
        // 方法2: 如果shutdown脚本失败，尝试通过端口强制关闭Tomcat进程（仅对当前配置端口生效）
        // 说明：通过 lsof 根据端口号查找占用进程并执行 kill -9，避免旧实例占用端口影响重部署。
        try {
            forceKillTomcatByPorts(os, ports);
        } catch (Exception e) {
            DeployLogWebSocket.sendLog("警告: 通过端口强制关闭Tomcat进程失败: " + e.getMessage());
            DeployLogWebSocket.sendLog("提示: 如果服务仍未关闭，请手动停止Tomcat服务");
        }
    }

    /**
     * 通过端口强制关闭Tomcat进程
     * 说明：
     * - 仅在非 Windows 系统上执行，Windows 环境仍建议手工停止服务；
     * - 使用 lsof -ti:port | xargs kill -9 的方式按端口号杀死占用进程；
     * - 仅对当前配置中的 HTTP 端口（或兜底的 8080）进行处理，降低误杀其他服务的风险。
     *
     * @param osType 操作系统类型
     * @param ports  端口配置列表
     */
    private void forceKillTomcatByPorts(String osType, List<PortConfig> ports) throws Exception {
        String os = osType != null ? osType : System.getProperty("os.name");
        if (os.toLowerCase().contains("win")) {
            DeployLogWebSocket.sendLog("提示: 当前为 Windows 环境，未自动执行强制 kill，请手动停止 Tomcat。");
            return;
        }

        java.util.Set<Integer> targetPorts = new java.util.HashSet<>();
        if (ports != null && !ports.isEmpty()) {
            for (PortConfig portConfig : ports) {
                String portType = portConfig.getType();
                Integer port = portConfig.getPort();
                if (portType != null && port != null &&
                        ("HTTP".equalsIgnoreCase(portType) || "CONNECTOR".equalsIgnoreCase(portType))) {
                    targetPorts.add(port);
                }
            }
        }
        // 如果未从配置中解析到端口，则兜底使用 8080
        if (targetPorts.isEmpty()) {
            targetPorts.add(8080);
        }

        for (Integer port : targetPorts) {
            if (port == null) {
                continue;
            }
            if (!PortUtil.isPortInUse(port)) {
                continue;
            }

            DeployLogWebSocket.sendLog("尝试通过端口强制关闭 Tomcat 进程，端口: " + port);
            // 使用 lsof 根据端口查找进程并强制 kill，-t 只输出 PID，-i:port 按端口筛选
            String cmd = String.format("bash -c \"lsof -ti:%d | xargs -r kill -9\"", port);
            try {
                Process killProcess = ProcessUtil.startProcess(cmd, null);
                ProcessUtil.waitForProcess(killProcess, 5000);
            } catch (Exception e) {
                DeployLogWebSocket.sendLog("警告: 通过端口 " + port + " 执行 kill 时发生异常: " + e.getMessage());
            }

            // 再次检测端口是否已释放
            if (!PortUtil.isPortInUse(port)) {
                DeployLogWebSocket.sendLog("端口 " + port + " 已释放，Tomcat 进程已被强制关闭。");
            } else {
                DeployLogWebSocket.sendLog("警告: 端口 " + port + " 仍被占用，请检查是否存在其他服务占用该端口。");
            }
        }
    }

    /**
     * 启动Tomcat服务并读取启动日志
     * @param tomcatDir Tomcat目录
     * @param osType 操作系统类型
     * @param ports 端口配置列表
     * @throws IOException IO异常
     */
    private void startTomcat(String tomcatDir, String osType, List<PortConfig> ports) throws IOException {
        String os = osType != null ? osType : System.getProperty("os.name");
        String scriptPath;
        
        if (os.toLowerCase().contains("win")) {
            // Windows系统使用startup.bat
            scriptPath = tomcatDir + File.separator + "bin" + File.separator + "startup.bat";
        } else {
            // Linux系统使用startup.sh
            scriptPath = tomcatDir + File.separator + "bin" + File.separator + "startup.sh";
            // 确保bin目录下所有Shell脚本具有执行权限（避免startup.sh内部调用其他脚本如catalina.sh时因无权限失败）
            try {
                String binDir = tomcatDir + File.separator + "bin";
                String chmodCmd = String.format("chmod +x \"%s\"/*.sh", binDir);
                Process chmodProcess = ProcessUtil.startProcess(chmodCmd, null);
                chmodProcess.waitFor();
            } catch (Exception e) {
                DeployLogWebSocket.sendLog("警告: 无法为 Tomcat bin 目录批量设置执行权限: " + e.getMessage());
            }
        }

        File scriptFile = new File(scriptPath);
        if (!scriptFile.exists()) {
            throw new IOException("Tomcat启动脚本不存在: " + scriptPath);
        }

        // 启动Tomcat（后台运行）
        DeployLogWebSocket.sendLog("执行启动命令: " + scriptPath);
        Process process = ProcessUtil.startProcess(scriptPath, tomcatDir);
        
        // 读取启动脚本的初始输出
        try {
            // 等待一小段时间让启动脚本开始执行
            Thread.sleep(500);
            
            // 读取启动脚本的输出（非阻塞方式）
            readStartupOutput(process);
            
            DeployLogWebSocket.sendLog("Tomcat启动命令已执行，正在读取启动日志...");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            DeployLogWebSocket.sendLog("警告: 读取启动输出被中断");
        }
        
        // 读取Tomcat日志文件并检测启动状态
        readTomcatLogsAndCheckStatus(tomcatDir, ports);
    }

    /**
     * 读取启动脚本的输出
     * @param process 启动进程
     */
    private void readStartupOutput(Process process) {
        try {
            // 使用线程异步读取输出，避免阻塞
            Thread outputThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), "UTF-8"))) {
                    String line;
                    int lineCount = 0;
                    // 只读取前20行，避免阻塞太久
                    while ((line = reader.readLine()) != null && lineCount < 20) {
                        if (line.trim().length() > 0) {
                            DeployLogWebSocket.sendLog("[启动脚本] " + line);
                        }
                        lineCount++;
                    }
                } catch (IOException e) {
                    // 忽略读取错误
                }
            });
            outputThread.setDaemon(true);
            outputThread.start();
            
            // 等待最多2秒
            outputThread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 读取Tomcat日志文件并检测启动状态
     * @param tomcatDir Tomcat目录
     * @param ports 端口配置列表
     */
    private void readTomcatLogsAndCheckStatus(String tomcatDir, List<PortConfig> ports) {
        String logsDir = tomcatDir + File.separator + "logs";
        File logsDirFile = new File(logsDir);
        
        if (!logsDirFile.exists()) {
            DeployLogWebSocket.sendLog("警告: Tomcat日志目录不存在: " + logsDir);
            // 仍然尝试检测端口
            checkTomcatStartupStatus(ports, 0);
            return;
        }
        
        // 查找最新的catalina日志文件
        File catalinaLog = findLatestCatalinaLog(logsDirFile);
        boolean hasLogFile = (catalinaLog != null && catalinaLog.exists());
        
        if (hasLogFile) {
            DeployLogWebSocket.sendLog("正在读取Tomcat启动日志: " + catalinaLog.getName());
        } else {
            DeployLogWebSocket.sendLog("未找到Tomcat日志文件，将通过端口检测启动状态");
        }
        
        // 读取日志文件并检测启动状态
        long lastPosition = 0;
        int maxWaitTime = 120; // 最多等待120秒
        int waitCount = 0;
        boolean startupDetected = false;
        
        while (waitCount < maxWaitTime) {
            try {
                // 如果日志文件存在，读取新增的日志内容
                if (hasLogFile && catalinaLog.exists()) {
                    long currentSize = catalinaLog.length();
                    if (currentSize > lastPosition) {
                        try (BufferedReader reader = new BufferedReader(
                                new InputStreamReader(
                                        new java.io.FileInputStream(catalinaLog), "UTF-8"))) {
                            // 跳过已读内容
                            if (lastPosition > 0) {
                                reader.skip(lastPosition);
                            }
                            
                            String line;
                            while ((line = reader.readLine()) != null) {
                                if (line.trim().length() > 0) {
                                    DeployLogWebSocket.sendLog("[Tomcat日志] " + line);
                                    
                                    // 检测启动成功的关键字
                                    String lineLower = line.toLowerCase();
                                    if (lineLower.contains("server startup") || 
                                        lineLower.contains("started") ||
                                        lineLower.contains("startup in")) {
                                        startupDetected = true;
                                    }
                                }
                            }
                            lastPosition = currentSize;
                        }
                    }
                }
                
                // 检测端口是否启动（每5秒检测一次，避免频繁检测）
                if (waitCount % 5 == 0 || waitCount < 5) {
                    if (checkTomcatStartupStatus(ports, waitCount)) {
                        DeployLogWebSocket.sendLog("✓ Tomcat服务启动成功！");
                        return;
                    }
                }
                
                // 如果检测到启动关键字，再等待几秒确认端口启动
                if (startupDetected && waitCount > 10) {
                    // 再等待5秒确认
                    for (int i = 0; i < 5; i++) {
                        Thread.sleep(1000);
                        if (checkTomcatStartupStatus(ports, waitCount + i)) {
                            DeployLogWebSocket.sendLog("✓ Tomcat服务启动成功！");
                            return;
                        }
                    }
                }
                
                Thread.sleep(1000);
                waitCount++;
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                DeployLogWebSocket.sendLog("警告: 读取日志被中断");
                break;
            } catch (Exception e) {
                DeployLogWebSocket.sendLog("警告: 读取日志文件出错: " + e.getMessage());
                // 如果读取出错，继续通过端口检测
                if (checkTomcatStartupStatus(ports, waitCount)) {
                    DeployLogWebSocket.sendLog("✓ Tomcat服务启动成功！");
                    return;
                }
                waitCount++;
            }
        }
        
        // 超时后最后检测一次
        if (checkTomcatStartupStatus(ports, waitCount)) {
            DeployLogWebSocket.sendLog("✓ Tomcat服务启动成功！");
        } else {
            DeployLogWebSocket.sendLog("警告: 等待超时（" + maxWaitTime + "秒），Tomcat可能未完全启动，请检查日志文件");
        }
    }

    /**
     * 查找最新的catalina日志文件
     * @param logsDir 日志目录
     * @return 最新的日志文件，如果不存在返回null
     */
    private File findLatestCatalinaLog(File logsDir) {
        File[] files = logsDir.listFiles();
        if (files == null) {
            return null;
        }
        
        File latestFile = null;
        long latestTime = 0;
        
        for (File file : files) {
            if (file.isFile() && file.getName().startsWith("catalina.") && 
                (file.getName().endsWith(".log") || file.getName().endsWith(".out"))) {
                long modifiedTime = file.lastModified();
                if (modifiedTime > latestTime) {
                    latestTime = modifiedTime;
                    latestFile = file;
                }
            }
        }
        
        return latestFile;
    }

    /**
     * 检测Tomcat启动状态（通过端口检测）
     * @param ports 端口配置列表
     * @param waitCount 已等待的秒数
     * @return true表示启动成功，false表示未启动
     */
    private boolean checkTomcatStartupStatus(List<PortConfig> ports, int waitCount) {
        if (ports != null && !ports.isEmpty()) {
            for (PortConfig portConfig : ports) {
                String portType = portConfig.getType();
                Integer port = portConfig.getPort();
                
                if (portType != null && port != null) {
                    // 检测HTTP端口
                    if ("HTTP".equalsIgnoreCase(portType) || "CONNECTOR".equalsIgnoreCase(portType)) {
                        if (PortUtil.isPortInUse(port)) {
                            if (waitCount > 0 && waitCount % 10 == 0) {
                                DeployLogWebSocket.sendLog("检测到端口 " + port + " 已启动，Tomcat正在启动中...");
                            }
                            return true;
                        }
                    }
                }
            }
        } else {
            // 如果没有配置端口，检测默认端口8080
            if (PortUtil.isPortInUse(8080)) {
                if (waitCount > 0 && waitCount % 10 == 0) {
                    DeployLogWebSocket.sendLog("检测到默认端口 8080 已启动，Tomcat正在启动中...");
                }
                return true;
            }
        }
        
        return false;
    }
}

