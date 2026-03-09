package com.deploy.model;

import lombok.Data;

/**
 * 端口配置模型
 */
@Data
public class PortConfig {
    /**
     * 端口名称（如：HTTP端口、AJP端口等）
     */
    private String name;

    /**
     * 端口号
     */
    private Integer port;

    /**
     * 端口类型（HTTP、AJP、SHUTDOWN等）
     */
    private String type;
}

