package com.deploy.controller;

import com.deploy.model.DeployConfig;
import com.deploy.model.DatabaseConfig;
import com.deploy.service.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 部署相关API接口控制器
 */
@RestController
@RequestMapping("/api/deploy")
@CrossOrigin(origins = "*")
public class DeployController {

    @Autowired
    private DeployService deployService;

    @Autowired
    private ConfigService configService;

    @Autowired
    private ScriptService scriptService;

    @Autowired
    private ServiceCheckService serviceCheckService;

    /**
     * 检查当前运行环境的 JDK 版本
     * 说明：
     * - 前端在开始部署 Tomcat 之前调用本接口；
     * - 若当前 JDK 主版本号 >= 17，则可直接用于 Tomcat 部署；
     * - 若小于 17（例如 1.8 / 11），则前端需引导用户手动输入 JDK17 安装目录，并在后续部署请求中传入 tomcatJdkHome。
     */
    @GetMapping("/java/check")
    public ResponseEntity<Map<String, Object>> checkJavaVersion(
            @RequestParam(value = "jdkHome", required = false) String jdkHome) {
        Map<String, Object> result = new HashMap<>();
        try {
            String javaExecutable;
            if (jdkHome != null && !jdkHome.trim().isEmpty()) {
                // 若传入了专用 JDK 路径，则优先使用该路径下的 java
                File home = new File(jdkHome.trim());
                File binJava = new File(new File(home, "bin"), "java.exe");
                if (!binJava.exists()) {
                    binJava = new File(new File(home, "bin"), "java");
                }
                javaExecutable = binJava.getAbsolutePath();
            } else {
                // 否则使用系统 PATH 中的 java
                javaExecutable = "java";
            }

            // 1. 调用 java -version 获取版本信息（通常输出到 stderr）
            Process process = new ProcessBuilder(javaExecutable, "-version").redirectErrorStream(true).start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.trim().isEmpty()) {
                        output.append(line).append('\n');
                    }
                }
            }
            int exitCode = process.waitFor();

            // 2. 解析版本号：优先从 java -version 输出中提取，其次回退到 System.getProperty("java.version")
            String rawOutput = output.toString().trim();
            String version = parseJavaVersion(rawOutput);
            if (version == null || version.isEmpty()) {
                version = System.getProperty("java.version");
            }
            if (version == null) {
                version = "unknown";
            }

            boolean atLeast17 = isAtLeastJava17(version);

            result.put("success", exitCode == 0 && atLeast17);
            result.put("javaVersion", version);
            result.put("rawOutput", rawOutput);
            result.put("isJdk17", atLeast17);
            result.put("message", atLeast17
                    ? "当前 JDK 版本满足 Tomcat 部署要求（需要 JDK 17 或更高版本）"
                    : "当前 JDK 版本不满足 Tomcat 部署要求（需要 JDK 17 或更高版本）");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("success", false);
            result.put("javaVersion", System.getProperty("java.version", "unknown"));
            result.put("isJdk17", false);
            result.put("message", "检测 JDK 版本失败: " + e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * 从 java -version 输出中解析版本号字符串
     * 示例输出：
     *   java version "1.8.0_65"
     *   openjdk version "17.0.10" 2024-01-16
     */
    private String parseJavaVersion(String rawOutput) {
        if (rawOutput == null || rawOutput.isEmpty()) {
            return null;
        }
        // 只关注第一行
        String firstLine = rawOutput.split("\\r?\\n")[0];
        int idxQuote = firstLine.indexOf('"');
        if (idxQuote >= 0) {
            int idxQuote2 = firstLine.indexOf('"', idxQuote + 1);
            if (idxQuote2 > idxQuote) {
                return firstLine.substring(idxQuote + 1, idxQuote2);
            }
        }
        // 回退：尝试基于空格拆分
        String[] parts = firstLine.split("\\s+");
        for (String part : parts) {
            if (part.matches("\\d+(\\.\\d+.*)?")) {
                return part;
            }
        }
        return null;
    }

    /**
     * 判断给定的 Java 版本号是否满足 JDK 17 及以上
     * 兼容版本格式：
     *   - 1.8.0_65  => 主版本 8
     *   - 11.0.23   => 主版本 11
     *   - 17.0.10   => 主版本 17
     */
    private boolean isAtLeastJava17(String version) {
        if (version == null || version.isEmpty()) {
            return false;
        }
        try {
            int major;
            if (version.startsWith("1.")) {
                // 旧格式：1.x.y
                String[] parts = version.split("\\.");
                major = (parts.length > 1) ? Integer.parseInt(parts[1]) : 1;
            } else {
                // 新格式：17.0.10 / 11.0.23 等
                String[] parts = version.split("\\.");
                major = Integer.parseInt(parts[0]);
            }
            return major >= 17;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * 检查安装目录是否存在
     * @param path 安装目录路径
     * @return 检查结果（exists: true/false）
     */
    @GetMapping("/installDir/check")
    public ResponseEntity<Map<String, Object>> checkInstallDir(@RequestParam("path") String path) {
        Map<String, Object> result = new HashMap<>();

        if (path == null || path.trim().isEmpty()) {
            result.put("success", false);
            result.put("message", "安装目录路径不能为空");
            return ResponseEntity.badRequest().body(result);
        }

        File dir = new File(path);
        boolean exists = dir.exists() && dir.isDirectory();

        result.put("success", true);
        result.put("exists", exists);
        result.put("message", exists ? "安装目录存在: " + path : "安装目录不存在: " + path);
        return ResponseEntity.ok(result);
    }

    /**
     * 检查TongWeb部署目录是否符合约定结构
     * 说明：当前约定部署目录形如 /usr/local/TongWeb8.0.9.09/domains/domain1/deployment，
     *      本接口会根据该目录层级回溯定位 TongWeb 安装根目录，并检查 bin 目录下是否存在 startd.sh / stopserver.sh。
     *
     * @param path 部署目录路径
     * @return 检查结果（exists: true/false，binExists: true/false，scriptsValid: true/false）
     */
    @GetMapping("/tongweb/installDir/check")
    public ResponseEntity<Map<String, Object>> checkTongWebInstallDir(@RequestParam("path") String path) {
        Map<String, Object> result = new HashMap<>();

        if (path == null || path.trim().isEmpty()) {
            result.put("success", false);
            result.put("message", "部署目录路径不能为空");
            return ResponseEntity.badRequest().body(result);
        }

        File deployDir = new File(path);
        if (!deployDir.exists() || !deployDir.isDirectory()) {
            result.put("success", true);
            result.put("exists", false);
            result.put("binExists", false);
            result.put("scriptsValid", false);
            result.put("message", "部署目录不存在或不是目录: " + path);
            return ResponseEntity.ok(result);
        }

        // 按 domains/domain1/deployment 层级回溯推导 TongWeb 安装根目录
        File domain1Dir = deployDir.getParentFile();
        File domainsDir = (domain1Dir != null ? domain1Dir.getParentFile() : null);
        File tongWebHome = (domainsDir != null ? domainsDir.getParentFile() : null);

        if (tongWebHome == null || !tongWebHome.exists() || !tongWebHome.isDirectory()) {
            result.put("success", true);
            result.put("exists", true);
            result.put("binExists", false);
            result.put("scriptsValid", false);
            result.put("message", "未能根据部署目录推导出TongWeb安装目录，请确认目录层级是否为 domains/domain1/deployment。");
            return ResponseEntity.ok(result);
        }

        File binDir = new File(tongWebHome, "bin");
        boolean binExists = binDir.exists() && binDir.isDirectory();
        File startScript = new File(binDir, "startd.sh");
        File stopScript = new File(binDir, "stopserver.sh");
        boolean scriptsValid = startScript.exists() && stopScript.exists();

        result.put("success", true);
        result.put("exists", true);
        result.put("binExists", binExists);
        result.put("scriptsValid", scriptsValid);
        result.put("tongWebHome", tongWebHome.getAbsolutePath());
        result.put("binDir", binExists ? binDir.getAbsolutePath() : null);

        if (!binExists) {
            result.put("message", "已找到部署目录，但未找到TongWeb bin目录，请确认安装目录结构是否正确。");
        } else if (!scriptsValid) {
            result.put("message", "已找到TongWeb bin目录，但缺少 startd.sh 或 stopserver.sh，请确认TongWeb安装是否完整。");
        } else {
            result.put("message", "TongWeb部署目录与安装结构检查通过。");
        }

        return ResponseEntity.ok(result);
    }

    /**
     * 开始部署（只使用资源目录下的WAR包）
     * @param config 部署配置
     * @return 响应结果
     */
    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> startDeploy(@RequestBody DeployConfig config) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            // 验证配置
            String validationError = configService.validateConfig(config);
            if (validationError != null) {
                result.put("success", false);
                result.put("message", validationError);
                return ResponseEntity.badRequest().body(result);
            }

            // 确保使用内置WAR包
            config.setUseBuiltInWars(true);

            // 异步执行部署（无需保存Future对象）
            deployService.startDeploy(config);
            
            result.put("success", true);
            result.put("message", "部署任务已启动");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            e.printStackTrace();
            result.put("success", false);
            result.put("message", "启动部署失败: " + e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * 服务检测
     * @param port 端口号
     * @return 检测结果
     */
    @GetMapping("/check/{port}")
    public ResponseEntity<Map<String, Object>> checkService(@PathVariable int port) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            String message = serviceCheckService.checkService(port);
            boolean isRunning = message.contains("已启动");
            
            result.put("success", true);
            result.put("running", isRunning);
            result.put("message", message);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "服务检测失败: " + e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * 服务检测（指定主机和端口）
     * @param host 主机地址
     * @param port 端口号
     * @return 检测结果
     */
    @GetMapping("/check")
    public ResponseEntity<Map<String, Object>> checkServiceWithHost(
            @RequestParam String host,
            @RequestParam int port) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            String message = serviceCheckService.checkService(host, port);
            boolean isRunning = message.contains("已启动");
            
            result.put("success", true);
            result.put("running", isRunning);
            result.put("message", message);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "服务检测失败: " + e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * 执行初始化脚本
     * @param request 请求参数（包含数据库配置和脚本文件列表）
     * @return 执行结果
     */
    @PostMapping("/script/execute")
    public ResponseEntity<Map<String, Object>> executeScript(@RequestBody Map<String, Object> request) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            // 解析数据库配置
            @SuppressWarnings("unchecked")
            Map<String, Object> dbConfigMap = (Map<String, Object>) request.get("databaseConfig");
            if (dbConfigMap == null) {
                result.put("success", false);
                result.put("message", "数据库配置不能为空");
                return ResponseEntity.badRequest().body(result);
            }

            DatabaseConfig databaseConfig = new DatabaseConfig();
            databaseConfig.setType((String) dbConfigMap.get("type"));
            databaseConfig.setConnectionString((String) dbConfigMap.get("connectionString"));
            databaseConfig.setUsername((String) dbConfigMap.get("username"));
            databaseConfig.setPassword((String) dbConfigMap.get("password"));
            
            // 兼容旧格式（host/port/database）
            if (dbConfigMap.get("host") != null) {
                databaseConfig.setHost((String) dbConfigMap.get("host"));
            }
            if (dbConfigMap.get("port") != null) {
                databaseConfig.setPort(((Number) dbConfigMap.get("port")).intValue());
            }
            if (dbConfigMap.get("database") != null) {
                databaseConfig.setDatabase((String) dbConfigMap.get("database"));
            }

            // 解析脚本文件列表
            @SuppressWarnings("unchecked")
            List<String> scriptFiles = (List<String>) request.get("scriptFiles");

            // 执行脚本
            scriptService.executeScripts(databaseConfig, scriptFiles);

            result.put("success", true);
            result.put("message", "脚本执行完成");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "脚本执行失败: " + e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * 保存配置
     * @param request 包含配置名称和配置对象的请求
     * @return 保存结果
     */
    @PostMapping("/config/save")
    public ResponseEntity<Map<String, Object>> saveConfig(@RequestBody Map<String, Object> request) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            String configName = (String) request.get("configName");
            if (configName == null || configName.trim().isEmpty()) {
                result.put("success", false);
                result.put("message", "配置名称不能为空");
                return ResponseEntity.badRequest().body(result);
            }
            
            @SuppressWarnings("unchecked")
            Map<String, Object> configMap = (Map<String, Object>) request.get("config");
            if (configMap == null) {
                result.put("success", false);
                result.put("message", "配置不能为空");
                return ResponseEntity.badRequest().body(result);
            }
            
            // 将Map转换为DeployConfig对象
            com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
            DeployConfig config = objectMapper.convertValue(configMap, DeployConfig.class);
            
            configService.saveConfig(configName.trim(), config);
            result.put("success", true);
            result.put("message", "配置保存成功");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            e.printStackTrace(); // 打印异常堆栈以便调试
            result.put("success", false);
            result.put("message", "配置保存失败: " + e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * 加载配置
     * @param configName 配置名称
     * @return 配置信息
     */
    @GetMapping("/config/load/{configName}")
    public ResponseEntity<Map<String, Object>> loadConfig(@PathVariable String configName) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            DeployConfig config = configService.loadConfig(configName);
            if (config == null) {
                result.put("success", false);
                result.put("message", "未找到配置: " + configName);
                return ResponseEntity.ok(result);
            }
            
            result.put("success", true);
            result.put("config", config);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "配置加载失败: " + e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * 获取配置列表
     * @return 配置名称列表
     */
    @GetMapping("/config/list")
    public ResponseEntity<Map<String, Object>> listConfigs() {
        Map<String, Object> result = new HashMap<>();
        
        try {
            List<String> configNames = configService.listConfigs();
            result.put("success", true);
            result.put("configs", configNames);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "获取配置列表失败: " + e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }
}

