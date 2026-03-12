package com.deploy.service;

import com.deploy.model.DmConnectionRequest;
import com.deploy.model.DmObjectItem;
import com.deploy.util.DmJdbcDriverLoader;
import com.deploy.websocket.DeployLogWebSocket;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.*;

/**
 * 达梦元数据服务
 * 说明：用于“导出向导”阶段拉取 schema（用户模式）与对象清单（表/视图/序列/同义词/过程/函数/触发器）。
 */
@Service
public class DmMetadataService {

    /**
     * 列出所有用户模式（schema）
     * 说明：使用 JDBC 元数据 getSchemas()，并过滤常见系统 schema，避免用户误选系统对象。
     */
    public List<String> listSchemas(DmConnectionRequest req) throws Exception {
        try (Connection conn = open(req)) {
            DatabaseMetaData meta = conn.getMetaData();
            List<String> schemas = new ArrayList<>();
            try (ResultSet rs = meta.getSchemas()) {
                while (rs.next()) {
                    String schema = rs.getString("TABLE_SCHEM");
                    if (schema == null || schema.trim().isEmpty()) {
                        continue;
                    }
                    if (isSystemSchema(schema)) {
                        continue;
                    }
                    schemas.add(schema);
                }
            }
            Collections.sort(schemas);
            return schemas;
        }
    }

    /**
     * 列出指定 schema 下的对象清单
     * 说明：
     * - 表/视图：优先用 JDBC 元数据 getTables()
     * - 过程/函数：优先用 JDBC 元数据 getProcedures()/getFunctions()
     * - 序列/同义词/触发器：JDBC 元数据支持不一致，这里尝试通过 DM 的 ALL_* 视图查询（失败则返回空并输出日志）。
     */
    public Map<String, List<DmObjectItem>> listObjects(DmConnectionRequest req, List<String> schemas) throws Exception {
        if (schemas == null || schemas.isEmpty()) {
            throw new IllegalArgumentException("schemas 不能为空");
        }
        Map<String, List<DmObjectItem>> result = new LinkedHashMap<>();

        try (Connection conn = open(req)) {
            DatabaseMetaData meta = conn.getMetaData();
            String catalog = safe(conn.getCatalog());

            for (String schema : schemas) {
                List<DmObjectItem> items = new ArrayList<>();

                // TABLE / VIEW
                try (ResultSet rs = meta.getTables(catalog, schema, "%", new String[]{"TABLE", "VIEW"})) {
                    while (rs.next()) {
                        String name = rs.getString("TABLE_NAME");
                        String type = rs.getString("TABLE_TYPE");
                        if (name == null || name.trim().isEmpty()) continue;
                        if (type == null) type = "TABLE";

                        DmObjectItem it = new DmObjectItem();
                        it.setSchema(schema);
                        it.setName(name);
                        it.setType(type.toUpperCase());
                        items.add(it);
                    }
                }

                // PROCEDURE
                try (ResultSet rs = meta.getProcedures(catalog, schema, "%")) {
                    while (rs.next()) {
                        String name = rs.getString("PROCEDURE_NAME");
                        if (name == null || name.trim().isEmpty()) continue;
                        DmObjectItem it = new DmObjectItem();
                        it.setSchema(schema);
                        it.setName(name);
                        it.setType("PROCEDURE");
                        items.add(it);
                    }
                } catch (Exception e) {
                    DeployLogWebSocket.sendLog("[mock-export] 提示: 读取 PROCEDURE 元数据失败（将忽略）: " + e.getMessage());
                }

                // FUNCTION
                try (ResultSet rs = meta.getFunctions(catalog, schema, "%")) {
                    while (rs.next()) {
                        String name = rs.getString("FUNCTION_NAME");
                        if (name == null || name.trim().isEmpty()) continue;
                        DmObjectItem it = new DmObjectItem();
                        it.setSchema(schema);
                        it.setName(name);
                        it.setType("FUNCTION");
                        items.add(it);
                    }
                } catch (Exception e) {
                    DeployLogWebSocket.sendLog("[mock-export] 提示: 读取 FUNCTION 元数据失败（将忽略）: " + e.getMessage());
                }

                // SEQUENCE / SYNONYM / TRIGGER：尽量用 DM 的 ALL_* 视图查询
                items.addAll(queryAllObjects(conn, schema, "SEQUENCE", "ALL_SEQUENCES", "SEQUENCE_NAME"));
                items.addAll(queryAllObjects(conn, schema, "SYNONYM", "ALL_SYNONYMS", "SYNONYM_NAME"));
                items.addAll(queryAllObjects(conn, schema, "TRIGGER", "ALL_TRIGGERS", "TRIGGER_NAME"));

                // 排序：type + name
                items.sort((a, b) -> {
                    int c = safe(a.getType()).compareToIgnoreCase(safe(b.getType()));
                    if (c != 0) return c;
                    return safe(a.getName()).compareToIgnoreCase(safe(b.getName()));
                });

                result.put(schema, items);
            }
        }

        return result;
    }

    /**
     * 打开达梦连接
     * 说明：仅支持达梦，按项目现有策略尝试加载多种驱动类名。
     */
    private Connection open(DmConnectionRequest req) throws Exception {
        if (req == null) throw new IllegalArgumentException("请求不能为空");
        if (req.getJdbcUrl() == null || req.getJdbcUrl().trim().isEmpty()) throw new IllegalArgumentException("jdbcUrl 不能为空");
        if (req.getUsername() == null || req.getUsername().trim().isEmpty()) throw new IllegalArgumentException("username 不能为空");
        if (req.getPassword() == null) throw new IllegalArgumentException("password 不能为空");

        // 说明：达梦驱动可能以外部 jar（lib/）提供，这里做一次动态加载，避免 classpath 未包含导致无法连接。
        DmJdbcDriverLoader.ensureDmDriverLoaded();
        // 说明：动态加载 jar 后需显式注册 Driver，否则可能出现 “No suitable driver found”。
        DmJdbcDriverLoader.ensureDriverRegistered(req.getJdbcUrl().trim());
        DriverManager.setLoginTimeout(15);
        Properties props = new Properties();
        props.setProperty("user", req.getUsername());
        props.setProperty("password", req.getPassword());
        return DriverManager.getConnection(req.getJdbcUrl().trim(), props);
    }

    private boolean isSystemSchema(String schema) {
        String s = schema.toUpperCase(Locale.ROOT);
        // 说明：过滤常见系统 schema，避免用户误选系统对象；如现场需要可白名单/黑名单化。
        return s.startsWith("SYS")
                || "PUBLIC".equals(s)
                || "DBA".equals(s)
                || "GUEST".equals(s);
    }

    private List<DmObjectItem> queryAllObjects(Connection conn, String schema, String type, String viewName, String colName) {
        List<DmObjectItem> items = new ArrayList<>();
        String sql = "SELECT " + colName + " FROM " + viewName + " WHERE OWNER = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString(1);
                    if (name == null || name.trim().isEmpty()) continue;
                    DmObjectItem it = new DmObjectItem();
                    it.setSchema(schema);
                    it.setName(name);
                    it.setType(type);
                    items.add(it);
                }
            }
        } catch (Exception e) {
            // 说明：不同 DM 版本/兼容模式下系统视图可能差异；失败时不抛出，避免阻断导出向导。
            DeployLogWebSocket.sendLog("[mock-export] 提示: 查询 " + viewName + " 失败（将忽略 " + type + " 清单）: " + e.getMessage());
        }
        return items;
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }
}

