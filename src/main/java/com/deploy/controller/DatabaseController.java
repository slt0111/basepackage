package com.deploy.controller;

import com.deploy.model.DatabaseConfig;
import com.deploy.websocket.DeployLogWebSocket;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * 数据库管理控制器
 */
@RestController
@RequestMapping("/api/database")
@CrossOrigin(origins = "*")
public class DatabaseController {

    /**
     * 测试数据库连接
     */
    @PostMapping("/test")
    public ResponseEntity<Map<String, Object>> testConnection(@RequestBody Map<String, Object> request) {
        Map<String, Object> result = new HashMap<>();
        Connection connection = null;
        
        try {
            String type = Objects.equals((String) request.get("type"), "达梦") ? "DM" : (String) request.get("type");
            String connectionString = (String) request.get("connectionString");
            String username = (String) request.get("username");
            String password = (String) request.get("password");
            
            if (connectionString == null || username == null || password == null) {
                result.put("success", false);
                result.put("message", "连接信息不完整");
                return ResponseEntity.badRequest().body(result);
            }
            
            // 加载数据库驱动
            loadDriver(type);
            
            // 设置连接属性，包括超时时间
            Properties props = new Properties();
            props.setProperty("user", username);
            props.setProperty("password", password);
            
            // 设置全局登录超时（对所有数据库类型有效）
            DriverManager.setLoginTimeout(10);
            
            // 根据数据库类型设置连接超时和登录超时
            String dbTypeUpper = type.toUpperCase();
            String finalConnectionString = connectionString;
            
            if ("DM".equals(dbTypeUpper) || "达梦".equals(type)) {
                // 达梦数据库特殊配置
                props.setProperty("loginTimeout", "10"); // 登录超时10秒
                props.setProperty("connectTimeout", "10000"); // 连接超时10秒
                // 如果连接字符串中没有指定超时参数，添加默认参数
                if (!connectionString.contains("loginTimeout") && !connectionString.contains("connectTimeout")) {
                    String separator = connectionString.contains("?") ? "&" : "?";
                    finalConnectionString = connectionString + separator + "loginTimeout=10&connectTimeout=10000";
                }
            } else if ("MYSQL".equals(dbTypeUpper)) {
                // MySQL配置
                props.setProperty("connectTimeout", "10000");
                props.setProperty("socketTimeout", "10000");
                if (!connectionString.contains("connectTimeout")) {
                    String separator = connectionString.contains("?") ? "&" : "?";
                    finalConnectionString = connectionString + separator + "connectTimeout=10000&socketTimeout=10000";
                }
            } else if ("ORACLE".equals(dbTypeUpper)) {
                // Oracle配置
                props.setProperty("oracle.net.CONNECT_TIMEOUT", "10000");
            } else if ("POSTGRESQL".equals(dbTypeUpper)) {
                // PostgreSQL配置
                props.setProperty("loginTimeout", "10");
                props.setProperty("connectTimeout", "10");
            }
            
            // 尝试连接
            connection = DriverManager.getConnection(finalConnectionString, props);
            
            // 验证连接是否有效
            if (connection != null && !connection.isClosed()) {
                // 执行一个简单的查询来验证连接
                try {
                    // 尝试获取数据库元数据来验证连接
                    String dbProductName = connection.getMetaData().getDatabaseProductName();
                    String dbVersion = connection.getMetaData().getDatabaseProductVersion();
                    result.put("success", true);
                    result.put("message", "数据库连接成功 - " + dbProductName + " " + dbVersion);
                    return ResponseEntity.ok(result);
                } catch (Exception e) {
                    // 如果获取元数据失败，但连接已建立，仍然认为连接成功
                    result.put("success", true);
                    result.put("message", "数据库连接成功（无法获取数据库信息）");
                    return ResponseEntity.ok(result);
                }
            } else {
                result.put("success", false);
                result.put("message", "无法建立连接");
                return ResponseEntity.ok(result);
            }
        } catch (ClassNotFoundException e) {
            result.put("success", false);
            result.put("message", "驱动加载失败: " + e.getMessage() + "。请确保数据库驱动已正确安装。");
            e.printStackTrace();
            return ResponseEntity.ok(result);
        } catch (java.sql.SQLException e) {
            result.put("success", false);
            String errorMsg = e.getMessage();
            // 提供更友好的错误信息
            if (errorMsg != null) {
                if (errorMsg.contains("网络通信异常") || errorMsg.contains("Network communication")) {
                    errorMsg = "网络通信异常，请检查：\n1. 数据库服务是否启动\n2. 网络连接是否正常\n3. 防火墙是否允许访问\n4. 连接字符串是否正确（格式：jdbc:dm://IP:端口/数据库名）";
                } else if (errorMsg.contains("连接超时") || errorMsg.contains("timeout")) {
                    errorMsg = "连接超时，请检查：\n1. 数据库服务是否可访问\n2. 网络是否畅通\n3. 端口是否正确";
                } else if (errorMsg.contains("用户名或密码错误") || errorMsg.contains("invalid credentials")) {
                    errorMsg = "用户名或密码错误";
                }
            }
            result.put("message", "连接失败: " + errorMsg);
            e.printStackTrace();
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "连接失败: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.ok(result);
        } finally {
            if (connection != null) {
                try {
                    connection.close();
                } catch (Exception e) {
                    // 忽略关闭异常
                }
            }
        }
    }

    /**
     * 加载数据库驱动
     */
    private void loadDriver(String type) throws ClassNotFoundException {
        if (type == null) {
            throw new IllegalArgumentException("数据库类型不能为空");
        }
        
        String driverClass = null;
        switch (type.toUpperCase()) {
            case "MYSQL":
                driverClass = "com.mysql.cj.jdbc.Driver";
                break;
            case "ORACLE":
                driverClass = "oracle.jdbc.driver.OracleDriver";
                break;
            case "POSTGRESQL":
                driverClass = "org.postgresql.Driver";
                break;
            case "达梦":
            case "DM":
                // 达梦数据库驱动类名（根据版本可能不同）
                // 尝试多个可能的驱动类名
                String[] dmDrivers = {
                    "dm.jdbc.driver.DmDriver",
                    "dm.jdbc.driver.DmDriver18",
                    "com.dameng.DmDriver"
                };
                ClassNotFoundException lastException = null;
                for (String driver : dmDrivers) {
                    try {
                        Class.forName(driver);
                        return; // 成功加载，直接返回
                    } catch (ClassNotFoundException e) {
                        lastException = e;
                    }
                }
                // 如果所有驱动类都加载失败，抛出异常
                throw new ClassNotFoundException("无法加载达梦数据库驱动，请确保DmJdbcDriver18.jar在lib目录下或已安装到Maven仓库。尝试的驱动类: " + String.join(", ", dmDrivers), lastException);
            default:
                throw new IllegalArgumentException("不支持的数据库类型: " + type);
        }
        
        if (driverClass != null) {
            Class.forName(driverClass);
        }
    }
}

