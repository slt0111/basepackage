package com.deploy.service;

import com.deploy.model.DeployConfig;
import com.deploy.util.FileUtil;
import com.deploy.util.WarPathUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 配置管理服务
 * 说明：配置目录与 wars 同级（JAR 同级或项目根下 configs），保存与读取均使用该目录。
 */
@Service
public class ConfigService {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 获取配置根目录（与 wars 同级）
     */
    private String getConfigDir() {
        return WarPathUtil.getConfigsBaseDir().toFile().getAbsolutePath();
    }

    /**
     * 保存部署配置到 configs 目录（与 wars 同级）
     * @param configName 配置名称
     * @param config 部署配置
     * @throws IOException IO异常
     */
    public void saveConfig(String configName, DeployConfig config) throws IOException {
        String configDir = getConfigDir();
        FileUtil.createDirectory(configDir);

        String fileName = sanitizeFileName(configName) + ".json";
        String filePath = configDir + File.separator + fileName;
        
        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(config);
        FileUtil.writeFileContent(filePath, json);
    }

    /**
     * 加载指定名称的配置
     * @param configName 配置名称
     * @return 部署配置，如果文件不存在则返回null
     */
    public DeployConfig loadConfig(String configName) {
        try {
            String fileName = sanitizeFileName(configName) + ".json";
            String filePath = getConfigDir() + File.separator + fileName;
            
            if (!FileUtil.fileExists(filePath)) {
                return null;
            }
            String json = FileUtil.readFileContent(filePath);
            return objectMapper.readValue(json, DeployConfig.class);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 获取所有配置名称列表
     * @return 配置名称列表
     */
    public List<String> listConfigs() {
        List<String> configNames = new ArrayList<>();
        try {
            File configDir = WarPathUtil.getConfigsBaseDir().toFile();
            if (configDir.exists() && configDir.isDirectory()) {
                File[] files = configDir.listFiles((dir, name) -> name.endsWith(".json"));
                if (files != null) {
                    for (File file : files) {
                        String fileName = file.getName();
                        // 移除.json后缀，恢复原始配置名称
                        String configName = fileName.substring(0, fileName.length() - 5);
                        configNames.add(configName);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return configNames;
    }

    /**
     * 清理文件名，移除特殊字符
     * @param fileName 原始文件名
     * @return 清理后的文件名
     */
    private String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return "default";
        }
        // 移除特殊字符，只保留字母、数字、中文、下划线和连字符
        return fileName.replaceAll("[^\\w\\u4e00-\\u9fa5-]", "_");
    }

    /**
     * 验证配置
     * @param config 部署配置
     * @return 验证结果消息，null表示验证通过
     */
    public String validateConfig(DeployConfig config) {
        if (config == null) {
            return "配置不能为空";
        }

        if (config.getInstallDir() == null || config.getInstallDir().isEmpty()) {
            return "部署目录不能为空";
        }

        if (config.getOsType() == null || config.getOsType().isEmpty()) {
            return "操作系统类型不能为空";
        }

        if (config.getMiddlewareType() == null || config.getMiddlewareType().isEmpty()) {
            return "中间件类型不能为空";
        }

        // TongWeb 也统一使用部署目录，无需单独的部署目录字段

        return null; // 验证通过
    }
}

