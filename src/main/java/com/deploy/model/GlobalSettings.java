package com.deploy.model;

/**
 * 全局设置模型（系统内置默认值可在此统一管理并可被用户覆盖）
 * 说明：用于“产品级全局设置”能力，将原先散落在前端/后端的硬编码默认值集中配置化。
 */
public class GlobalSettings {

    /**
     * 默认中间件类型（当未指定 middlewareType 时使用）
     */
    private String defaultMiddlewareType = "Tomcat";

    /**
     * Tomcat 相关默认设置
     */
    private Tomcat tomcat = new Tomcat();

    /**
     * 数据库相关默认设置
     */
    private Database database = new Database();

    /**
     * YML 占位符替换相关默认设置
     */
    private Yml yml = new Yml();

    public String getDefaultMiddlewareType() {
        return defaultMiddlewareType;
    }

    public void setDefaultMiddlewareType(String defaultMiddlewareType) {
        this.defaultMiddlewareType = defaultMiddlewareType;
    }

    public Tomcat getTomcat() {
        return tomcat;
    }

    public void setTomcat(Tomcat tomcat) {
        this.tomcat = tomcat;
    }

    public Database getDatabase() {
        return database;
    }

    public void setDatabase(Database database) {
        this.database = database;
    }

    public Yml getYml() {
        return yml;
    }

    public void setYml(Yml yml) {
        this.yml = yml;
    }

    public static class Tomcat {
        /**
         * 统一支撑 Tomcat 端口（用于心跳检测与默认 URL 构建）
         */
        private int unifiedPort = 8111;

        /**
         * 干部应用 Tomcat 端口（用于心跳检测与默认 URL 构建）
         */
        private int cadrePort = 8222;

        /**
         * authurl 使用的端口（当前逻辑默认使用统一支撑端口）
         */
        private int authPort = 8111;

        /**
         * 写入 YML 的 ${authurl} 是否去掉 http(s):// 前缀（保持兼容老模板写法）
         */
        private boolean authUrlStripProtocol = true;

        /**
         * Tomcat 专用 JDK 安装目录（可选）
         * 说明：
         * - 当目标环境要求 Tomcat 必须运行在 JDK 17 及以上版本时，
         *   可以在此配置一个 JDK17 安装目录，例如 C:\Java\jdk-17；
         * - 部署时若当前运行本工具的 JDK 版本不足 17，前端会要求用户在“参数配置”步骤中填写该路径，
         *   并通过全局设置持久化到此字段；
         * - 后端在部署 Tomcat 时会将该路径写入每个实例的 bin/setclasspath.bat 中的 JAVA_HOME，
         *   从而保证 Tomcat 以符合要求的 JDK 版本启动。
         */
        private String tomcatJdkHome;

        public int getUnifiedPort() {
            return unifiedPort;
        }

        public void setUnifiedPort(int unifiedPort) {
            this.unifiedPort = unifiedPort;
        }

        public int getCadrePort() {
            return cadrePort;
        }

        public void setCadrePort(int cadrePort) {
            this.cadrePort = cadrePort;
        }

        public int getAuthPort() {
            return authPort;
        }

        public void setAuthPort(int authPort) {
            this.authPort = authPort;
        }

        public boolean isAuthUrlStripProtocol() {
            return authUrlStripProtocol;
        }

        public void setAuthUrlStripProtocol(boolean authUrlStripProtocol) {
            this.authUrlStripProtocol = authUrlStripProtocol;
        }

        public String getTomcatJdkHome() {
            return tomcatJdkHome;
        }

        public void setTomcatJdkHome(String tomcatJdkHome) {
            this.tomcatJdkHome = tomcatJdkHome;
        }
    }

    public static class Database {
        /**
         * 默认数据库类型（当前前端默认达梦）
         */
        private String defaultType = "达梦";

        /**
         * 达梦连接串拼接模板：prefix + ip + suffix
         * 说明：用于替换前端硬编码的 jdbc:dm://{ip} 规则，便于按现场需要补端口/库名等。
         */
        private Dm dm = new Dm();

        public String getDefaultType() {
            return defaultType;
        }

        public void setDefaultType(String defaultType) {
            this.defaultType = defaultType;
        }

        public Dm getDm() {
            return dm;
        }

        public void setDm(Dm dm) {
            this.dm = dm;
        }

        public static class Dm {
            private String connectionPrefix = "jdbc:dm://";
            private String connectionSuffix = "";

            public String getConnectionPrefix() {
                return connectionPrefix;
            }

            public void setConnectionPrefix(String connectionPrefix) {
                this.connectionPrefix = connectionPrefix;
            }

            public String getConnectionSuffix() {
                return connectionSuffix;
            }

            public void setConnectionSuffix(String connectionSuffix) {
                this.connectionSuffix = connectionSuffix;
            }
        }
    }

    public static class Yml {
        /**
         * ${type} 的替换值（当前固定为 DruidDataSource）
         */
        private String datasourceTypeReplacement = "com.alibaba.druid.pool.DruidDataSource";

        public String getDatasourceTypeReplacement() {
            return datasourceTypeReplacement;
        }

        public void setDatasourceTypeReplacement(String datasourceTypeReplacement) {
            this.datasourceTypeReplacement = datasourceTypeReplacement;
        }
    }
}

