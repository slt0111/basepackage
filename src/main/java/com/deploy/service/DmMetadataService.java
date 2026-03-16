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
                // 说明：预先加载当前 schema 下对象注释映射，供后续对象条目复用，避免重复字典查询。
                Map<String, String> comments = loadObjectComments(conn, schema);

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
                        // 说明：为表/视图补充对象注释，前端可直接展示为“名称(注释)”。
                        it.setComment(comments.getOrDefault(name, null));
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
                        // 说明：从注释映射中获取过程注释（如果可用）
                        if (comments != null && comments.containsKey(name)) {
                            it.setComment(comments.get(name));
                        }
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
                        // 说明：从注释映射中获取函数注释（如果可用）
                        if (comments != null && comments.containsKey(name)) {
                            it.setComment(comments.get(name));
                        }
                        items.add(it);
                    }
                } catch (Exception e) {
                    DeployLogWebSocket.sendLog("[mock-export] 提示: 读取 FUNCTION 元数据失败（将忽略）: " + e.getMessage());
                }

                // SEQUENCE / SYNONYM / TRIGGER：尽量用 DM 的 ALL_* 视图查询
                items.addAll(queryAllObjects(conn, schema, "SEQUENCE", "ALL_SEQUENCES", "SEQUENCE_NAME", comments));
                items.addAll(queryAllObjects(conn, schema, "SYNONYM", "ALL_SYNONYMS", "SYNONYM_NAME", comments));
                items.addAll(queryAllObjects(conn, schema, "TRIGGER", "ALL_TRIGGERS", "TRIGGER_NAME", comments));

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

    private List<DmObjectItem> queryAllObjects(Connection conn, String schema, String type, String viewName, String colName, Map<String, String> comments) {
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
                    // 说明：从注释映射中获取对象注释
                    if (comments != null && comments.containsKey(name)) {
                        it.setComment(comments.get(name));
                    }
                    items.add(it);
                }
            }
        } catch (Exception e) {
            // 说明：不同 DM 版本/兼容模式下系统视图可能差异；失败时不抛出，避免阻断导出向导。
            DeployLogWebSocket.sendLog("[mock-export] 提示: 查询 " + viewName + " 失败（将忽略 " + type + " 清单）: " + e.getMessage());
        }
        return items;
    }

    /**
     * 加载指定 schema 下所有对象的注释映射
     * 说明：基于 ALL_TAB_COMMENTS 视图构建 name -> comment 映射，避免在遍历对象时重复查询字典。
     */
    private Map<String, String> loadObjectComments(Connection conn, String schema) {
        Map<String, String> map = new HashMap<>();
        // 1. 读取表和视图的注释
        String sql = "SELECT TABLE_NAME, COMMENTS FROM ALL_TAB_COMMENTS WHERE OWNER = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString(1);
                    String comment = rs.getString(2);
                    if (name == null || name.trim().isEmpty()) {
                        continue;
                    }
                    // 说明：只缓存非空注释，避免无意义空字符串污染前端展示逻辑。
                    if (comment != null && !comment.trim().isEmpty()) {
                        map.put(name, comment);
                    }
                }
            }
        } catch (Exception e) {
            // 说明：不同达梦版本/兼容模式下 ALL_TAB_COMMENTS 可能差异；失败时仅记录日志，不影响主流程。
            DeployLogWebSocket.sendLog("[mock-export] 提示: 读取表/视图注释失败 schema=" + schema + " err=" + e.getMessage());
        }
        
        // 2. 尝试读取序列的注释（如果存在 ALL_SEQUENCES 视图的 COMMENTS 列）
        try {
            String seqSql = "SELECT SEQUENCE_NAME, COMMENTS FROM ALL_SEQUENCES WHERE OWNER = ?";
            try (PreparedStatement ps = conn.prepareStatement(seqSql)) {
                ps.setString(1, schema);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String name = rs.getString(1);
                        String comment = rs.getString(2);
                        if (name == null || name.trim().isEmpty()) {
                            continue;
                        }
                        if (comment != null && !comment.trim().isEmpty()) {
                            map.put(name, comment);
                        }
                    }
                }
            }
        } catch (Exception e) {
            // 序列注释可能不可用，忽略错误
            DeployLogWebSocket.sendLog("[mock-export] 提示: 读取序列注释失败（将忽略） schema=" + schema + " err=" + e.getMessage());
        }
        
        // 3. 尝试读取过程和函数的注释（如果存在 ALL_PROCEDURES 视图）
        try {
            String procSql = "SELECT OBJECT_NAME, COMMENTS FROM ALL_PROCEDURES WHERE OWNER = ? AND OBJECT_TYPE IN ('PROCEDURE', 'FUNCTION')";
            try (PreparedStatement ps = conn.prepareStatement(procSql)) {
                ps.setString(1, schema);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String name = rs.getString(1);
                        String comment = rs.getString(2);
                        if (name == null || name.trim().isEmpty()) {
                            continue;
                        }
                        if (comment != null && !comment.trim().isEmpty()) {
                            map.put(name, comment);
                        }
                    }
                }
            }
        } catch (Exception e) {
            // 过程和函数注释可能不可用，忽略错误
            DeployLogWebSocket.sendLog("[mock-export] 提示: 读取过程/函数注释失败（将忽略） schema=" + schema + " err=" + e.getMessage());
        }
        
        return map;
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }
}

