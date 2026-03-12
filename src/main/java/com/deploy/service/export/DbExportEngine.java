package com.deploy.service.export;

import com.deploy.model.DatabaseConfig;
import com.deploy.websocket.DeployLogWebSocket;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * 数据库导出引擎
 * 说明：对单个数据库执行“每表 DDL + 每表 XML”导出，并返回统计信息用于 manifest。
 */
public class DbExportEngine {

    private final DbDdlExporter ddlExporter = new DbDdlExporter();
    private final DbXmlDataExporter xmlExporter = new DbXmlDataExporter();

    /**
     * 导出指定数据库
     *
     * @param dbKey 数据库标识（unified/cadre），用于输出目录
     * @param dbConfig 数据库连接配置（包含连接串/用户名/密码）
     * @param outDir 输出根目录（形如 .../<dbKey>/）
     * @return 统计信息（tables/rows 等）
     */
    public Map<String, Object> exportDatabase(String dbKey, DatabaseConfig dbConfig, Path outDir) throws Exception {
        Objects.requireNonNull(dbConfig, "databaseConfig不能为空");

        Files.createDirectories(outDir);
        Path ddlDir = outDir.resolve("ddl");
        Path dataDir = outDir.resolve("data");
        Files.createDirectories(ddlDir);
        Files.createDirectories(dataDir);

        DeployLogWebSocket.sendLog("[mock-export] 连接数据库: " + dbKey + " type=" + safe(dbConfig.getType()) + " url=" + maskConn(dbConfig.getConnectionString()));

        try (Connection conn = openConnection(dbConfig)) {
            DatabaseMetaData meta = conn.getMetaData();

            // 说明：schema/catalog 的处理在不同数据库差异较大，先以“尽量列出用户表”为目标，后续可按现场库进一步定制。
            String catalog = safe(meta.getConnection().getCatalog());
            String schema = null;

            List<String> tables = listUserTables(meta, catalog, schema);
            DeployLogWebSocket.sendLog("[mock-export] " + dbKey + " 发现表数量: " + tables.size());

            long totalRows = 0;
            for (String table : tables) {
                // 结构：每表一个 ddl.sql
                ddlExporter.exportTableDdl(conn, catalog, schema, table, ddlDir.resolve(table + ".ddl.sql"));
                // 数据：每表一个 xml
                long rows = xmlExporter.exportTable(conn, table, dataDir.resolve(table + ".xml"));
                totalRows += rows;
            }

            Map<String, Object> stat = new HashMap<>();
            stat.put("dbKey", dbKey);
            stat.put("type", safe(dbConfig.getType()));
            stat.put("connectionStringMasked", maskConn(dbConfig.getConnectionString()));
            stat.put("tables", tables.size());
            stat.put("rows", totalRows);
            return stat;
        }
    }

    private Connection openConnection(DatabaseConfig cfg) throws Exception {
        // 说明：尽量复用现有 DatabaseController 的驱动加载策略（达梦等），但不直接依赖 controller 层，避免循环依赖与职责混乱。
        loadDriverIfNeeded(cfg.getType());

        String url = cfg.getConnectionString();
        if (url == null || url.trim().isEmpty()) {
            throw new IllegalArgumentException("connectionString不能为空（请在配置模板中保存连接串）");
        }
        if (cfg.getUsername() == null || cfg.getPassword() == null) {
            throw new IllegalArgumentException("username/password不能为空（请在配置模板中保存账号密码）");
        }

        Properties props = new Properties();
        props.setProperty("user", cfg.getUsername());
        props.setProperty("password", cfg.getPassword());
        // 说明：设置全局登录超时，避免网络不通时导出卡死
        try {
            DriverManager.setLoginTimeout(15);
        } catch (Exception ignored) {
        }
        return DriverManager.getConnection(url, props);
    }

    private void loadDriverIfNeeded(String type) throws ClassNotFoundException {
        if (type == null) {
            return;
        }
        String t = type.toUpperCase();
        if ("MYSQL".equals(t)) {
            Class.forName("com.mysql.cj.jdbc.Driver");
            return;
        }
        if ("ORACLE".equals(t)) {
            Class.forName("oracle.jdbc.driver.OracleDriver");
            return;
        }
        if ("POSTGRESQL".equals(t)) {
            Class.forName("org.postgresql.Driver");
            return;
        }
        if ("DM".equals(t) || "达梦".equals(type)) {
            // 说明：达梦驱动类名在不同版本可能不同，按现有 DatabaseController 的策略逐一尝试。
            String[] dmDrivers = {"dm.jdbc.driver.DmDriver", "dm.jdbc.driver.DmDriver18", "com.dameng.DmDriver"};
            ClassNotFoundException last = null;
            for (String d : dmDrivers) {
                try {
                    Class.forName(d);
                    return;
                } catch (ClassNotFoundException e) {
                    last = e;
                }
            }
            throw new ClassNotFoundException("无法加载达梦数据库驱动，尝试: " + String.join(", ", dmDrivers), last);
        }
        // 说明：未知类型不强制加载，交给 DriverManager 自动发现（若依赖已包含则可用）。
    }

    private List<String> listUserTables(DatabaseMetaData meta, String catalog, String schema) throws Exception {
        List<String> tables = new ArrayList<>();
        // 说明：TABLE 过滤；部分库还会有 VIEW 等，这里先只导出表。
        try (ResultSet rs = meta.getTables(catalog, schema, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                String name = rs.getString("TABLE_NAME");
                if (name == null || name.trim().isEmpty()) {
                    continue;
                }
                tables.add(name);
            }
        }
        return tables;
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }

    private String maskConn(String conn) {
        if (conn == null) {
            return "";
        }
        // 说明：连接串一般不含密码，但仍做一次简单脱敏，避免用户把敏感参数拼在 URL 中。
        return conn.replaceAll("(?i)(password=)[^&;]+", "$1******");
    }
}

