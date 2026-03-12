package com.deploy.model;

import lombok.Data;

/**
 * 达梦连接请求模型
 * 说明：用于前端传递 JDBC 连接信息（DBA 用户），后端仅用于当前请求/任务，不会持久化密码。
 */
@Data
public class DmConnectionRequest {

    /**
     * JDBC 连接串
     * 示例：jdbc:dm://10.0.0.1:5236
     */
    private String jdbcUrl;

    /**
     * DBA 用户名（建议 SYSDBA / SYSTEM 等具备读取元数据权限的账号）
     */
    private String username;

    /**
     * DBA 密码
     */
    private String password;
}

