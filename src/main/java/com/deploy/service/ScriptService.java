package com.deploy.service;

import com.deploy.util.FileUtil;
import com.deploy.websocket.DeployLogWebSocket;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 初始化脚本执行服务
 * 支持多个SQL文件执行
 */
@Service
public class ScriptService {

    /**
     * 执行初始化脚本
     * @param databaseConfig 数据库配置
     * @param scriptFiles 脚本文件列表（相对于resources/scripts目录），如果为空则执行所有脚本
     * @throws Exception 执行异常
     */
    public void executeScripts(com.deploy.model.DatabaseConfig databaseConfig, List<String> scriptFiles) throws Exception {
        if (databaseConfig == null) {
            throw new IllegalArgumentException("数据库配置不能为空");
        }

        DeployLogWebSocket.sendLog("========== 开始执行初始化脚本 ==========");
        DeployLogWebSocket.sendLog("数据库类型: " + databaseConfig.getType());
        DeployLogWebSocket.sendLog("数据库地址: " + databaseConfig.getHost() + ":" + databaseConfig.getPort());
        DeployLogWebSocket.sendLog("数据库名称: " + databaseConfig.getDatabase());

        // 获取数据库连接
        Connection connection = getConnection(databaseConfig);
        try {
            // 获取要执行的脚本文件列表
            List<String> scriptsToExecute = getScriptFiles(scriptFiles);
            
            DeployLogWebSocket.sendLog("找到 " + scriptsToExecute.size() + " 个脚本文件");

            // 执行每个脚本文件
            for (String scriptFile : scriptsToExecute) {
                DeployLogWebSocket.sendLog("正在执行脚本: " + scriptFile);
                executeScriptFile(connection, scriptFile);
                DeployLogWebSocket.sendLog("脚本执行完成: " + scriptFile);
            }

            DeployLogWebSocket.sendLog("========== 所有脚本执行完成 ==========");
        } finally {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        }
    }

    /**
     * 获取数据库连接
     */
    private Connection getConnection(com.deploy.model.DatabaseConfig config) throws SQLException {
        String url = buildJdbcUrl(config);
        DeployLogWebSocket.sendLog("数据库连接URL: " + url.replace(config.getPassword(), "******"));
        
        return DriverManager.getConnection(url, config.getUsername(), config.getPassword());
    }

    /**
     * 构建JDBC URL
     */
    private String buildJdbcUrl(com.deploy.model.DatabaseConfig config) {
        // 优先使用连接串（新格式）
        if (config.getConnectionString() != null && !config.getConnectionString().isEmpty()) {
            return config.getConnectionString();
        }
        
        // 兼容旧格式（host/port/database）
        String type = config.getType() != null ? config.getType().toUpperCase() : "";
        String host = config.getHost();
        Integer port = config.getPort();
        String database = config.getDatabase();
        
        if (host == null || port == null || database == null) {
            throw new IllegalArgumentException("数据库配置不完整：需要提供连接串或host/port/database");
        }

        switch (type) {
            case "MYSQL":
                return String.format("jdbc:mysql://%s:%d/%s?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai",
                    host, port, database);
            case "ORACLE":
                return String.format("jdbc:oracle:thin:@%s:%d:%s", host, port, database);
            case "POSTGRESQL":
                return String.format("jdbc:postgresql://%s:%d/%s", host, port, database);
            case "达梦":
            case "DM":
                return String.format("jdbc:dm://%s:%d/%s", host, port, database);
            default:
                throw new IllegalArgumentException("不支持的数据库类型: " + type);
        }
    }

    /**
     * 获取要执行的脚本文件列表
     */
    private List<String> getScriptFiles(List<String> specifiedFiles) throws Exception {
        List<String> scriptFiles = new ArrayList<>();
        
        if (specifiedFiles != null && !specifiedFiles.isEmpty()) {
            // 使用指定的脚本文件
            for (String fileName : specifiedFiles) {
                scriptFiles.add("scripts/" + fileName);
            }
        } else {
            // 执行resources/scripts目录下的所有SQL文件
            // 注意：Spring Boot中无法直接列出resources目录下的文件
            // 这里需要手动指定或通过配置文件列出
            // 简化处理：假设有init.sql文件
            scriptFiles.add("scripts/init.sql");
        }
        
        return scriptFiles;
    }

    /**
     * 执行单个脚本文件
     */
    private void executeScriptFile(Connection connection, String scriptResourcePath) throws Exception {
        ClassPathResource resource = new ClassPathResource(scriptResourcePath);
        
        if (!resource.exists()) {
            DeployLogWebSocket.sendLog("警告: 脚本文件不存在: " + scriptResourcePath);
            return;
        }

        // 读取脚本内容
        StringBuilder scriptContent = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                scriptContent.append(line).append("\n");
            }
        }

        // 按分号分割SQL语句（简单处理，实际可能需要更复杂的SQL解析）
        String[] statements = scriptContent.toString().split(";");
        
        try (Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                sql = sql.trim();
                if (sql.isEmpty() || sql.startsWith("--") || sql.startsWith("/*")) {
                    continue; // 跳过空行和注释
                }
                
                try {
                    statement.execute(sql);
                    DeployLogWebSocket.sendLog("执行SQL: " + (sql.length() > 100 ? sql.substring(0, 100) + "..." : sql));
                } catch (SQLException e) {
                    DeployLogWebSocket.sendLog("SQL执行失败: " + e.getMessage());
                    DeployLogWebSocket.sendLog("SQL: " + (sql.length() > 100 ? sql.substring(0, 100) + "..." : sql));
                    // 根据需求决定是否继续执行或抛出异常
                    // throw e; // 如果需要严格模式，可以取消注释
                }
            }
        }
    }
}

