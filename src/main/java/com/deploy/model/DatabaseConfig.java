package com.deploy.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;

/**
 * 数据库配置模型
 */
@Data
public class DatabaseConfig {
    /**
     * 数据库名称/标识
     */
    private String name;

    /**
     * 数据库类型（MySQL、Oracle、PostgreSQL、达梦等）
     */
    private String type;

    /**
     * 数据库连接串（JDBC连接字符串）
     */
    private String connectionString;

    /**
     * 用户名
     */
    private String username;

    /**
     * 密码
     */
    private String password;

    // 保留以下字段以兼容旧配置（可选）
    /**
     * 数据库地址（前端可能传 ip，用 @JsonAlias 兼容）
     */
    @JsonAlias("ip")
    private String host;

    /**
     * 数据库端口
     */
    private Integer port;

    /**
     * 数据库名称
     */
    private String database;

    /**
     * 其他配置参数（JSON格式）
     */
    private String extraParams;
}

