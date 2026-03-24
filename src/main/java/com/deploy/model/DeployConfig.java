package com.deploy.model;

import lombok.Data;
import java.util.List;
import java.util.Map;

/**
 * 部署配置模型
 */
@Data
public class DeployConfig {
    /**
     * 安装目录
     */
    private String installDir;

    /**
     * 操作系统类型（Windows、Linux）
     */
    private String osType;

    /**
     * 中间件类型（Tomcat、TongWeb）
     */
    private String middlewareType;

    /**
     * 端口配置列表
     */
    private List<PortConfig> ports;

    /**
     * 数据库配置列表
     */
    private List<DatabaseConfig> databases;

    /**
     * WAR包列表（支持选择或使用内置）
     */
    private List<String> warFiles;

    /**
     * 是否使用内置WAR包
     */
    private Boolean useBuiltInWars = true;

    /**
     * TongWeb部署目录（仅TongWeb使用）
     */
    private String tongWebDeployDir;

    /**
     * 服务器 URL（IP+端口），用于替换 YML 中的 ${authurl}，如 http://10.200.58.167:8080
     */
    private String serverUrl;

    /**
     * YML配置文件内容（key为应用类型：unified/cadre）
     */
    private Map<String, String> ymlConfigs;

    /**
     * Tomcat 专用 JDK 安装目录
     * 说明：当中间件类型为 Tomcat 且在 Windows 上部署时，
     *      如果当前运行环境的 JDK 版本非 17，则要求用户在前端手动填写一个 JDK17 安装目录，
     *      后端在部署 Tomcat 时会将该路径写入对应实例的 bin/setclasspath.bat 中的 JAVA_HOME，
     *      确保 Tomcat 使用符合要求的 JDK 版本启动。
     */
    private String tomcatJdkHome;

    /**
     * 数据初始化参数：文件主目录（Windows）
     * 说明：用于替换初始化 SQL 模板中的 HZB_PATH 占位符。
     */
    private String hzbPath;

    /**
     * 数据初始化参数：文件主目录（Linux）
     * 说明：用于替换初始化 SQL 模板中的 HZB_PATH_LINUX 占位符。
     */
    private String hzbPathLinux;

    /**
     * 数据初始化参数：文件上传根目录
     * 说明：用于替换初始化 SQL 模板中的 UPLOAD_FILES_DIR 占位符。
     */
    private String uploadFilesDir;
}

