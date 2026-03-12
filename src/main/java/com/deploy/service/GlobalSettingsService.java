package com.deploy.service;

import com.deploy.model.GlobalSettings;
import com.deploy.util.FileUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.File;

/**
 * 全局设置服务：负责读取/保存全局设置（JSON 持久化）
 * 说明：将“系统内置默认值”集中管理，并允许用户在UI中覆盖这些默认值。
 */
@Service
public class GlobalSettingsService {

    /**
     * 全局设置落盘路径（相对项目/运行目录）
     * 说明：沿用项目其他“生成目录 generated/”的约定，便于统一管理与打包后运行。
     */
    private static final String SETTINGS_FILE_PATH = "generated/settings/global-settings.json";

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 获取全局设置（若文件不存在或解析失败，则返回带默认值的新对象）
     */
    public GlobalSettings getSettings() {
        try {
            if (!FileUtil.fileExists(SETTINGS_FILE_PATH)) {
                return new GlobalSettings(); // 默认设置（内置默认值）
            }
            String json = FileUtil.readFileContent(SETTINGS_FILE_PATH);
            if (json == null || json.trim().isEmpty()) {
                return new GlobalSettings(); // 空文件视为未配置，回退默认
            }
            GlobalSettings parsed = objectMapper.readValue(json, GlobalSettings.class);
            return normalize(parsed);
        } catch (Exception e) {
            // 解析失败时不影响主流程，直接回退默认设置
            return new GlobalSettings();
        }
    }

    /**
     * 保存全局设置（会自动做基础规范化与目录创建）
     */
    public GlobalSettings saveSettings(GlobalSettings settings) throws Exception {
        GlobalSettings normalized = normalize(settings);
        // 确保目录存在
        File file = new File(SETTINGS_FILE_PATH);
        File parent = file.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(normalized);
        FileUtil.writeFileContent(SETTINGS_FILE_PATH, json);
        return normalized;
    }

    /**
     * 规范化设置：补齐空对象/空字段，避免 NPE 并保证向后兼容
     */
    private GlobalSettings normalize(GlobalSettings settings) {
        if (settings == null) {
            return new GlobalSettings();
        }
        if (settings.getDefaultMiddlewareType() == null || settings.getDefaultMiddlewareType().trim().isEmpty()) {
            settings.setDefaultMiddlewareType("Tomcat");
        }
        if (settings.getTomcat() == null) {
            settings.setTomcat(new GlobalSettings.Tomcat());
        }
        // Tomcat JDK 路径允许为空（表示使用系统默认 JAVA_HOME），不做强制默认
        if (settings.getDatabase() == null) {
            settings.setDatabase(new GlobalSettings.Database());
        }
        if (settings.getDatabase().getDm() == null) {
            settings.getDatabase().setDm(new GlobalSettings.Database.Dm());
        }
        if (settings.getDatabase().getDefaultType() == null || settings.getDatabase().getDefaultType().trim().isEmpty()) {
            settings.getDatabase().setDefaultType("达梦");
        }
        if (settings.getDatabase().getDm().getConnectionPrefix() == null) {
            settings.getDatabase().getDm().setConnectionPrefix("jdbc:dm://");
        }
        if (settings.getDatabase().getDm().getConnectionSuffix() == null) {
            settings.getDatabase().getDm().setConnectionSuffix("");
        }
        if (settings.getYml() == null) {
            settings.setYml(new GlobalSettings.Yml());
        }
        if (settings.getYml().getDatasourceTypeReplacement() == null || settings.getYml().getDatasourceTypeReplacement().trim().isEmpty()) {
            settings.getYml().setDatasourceTypeReplacement("com.alibaba.druid.pool.DruidDataSource");
        }
        return settings;
    }
}

