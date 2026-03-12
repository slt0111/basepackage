package com.deploy.service;

import com.deploy.websocket.DeployLogWebSocket;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Locale;

/**
 * 达梦 DDL 解析器
 * 说明：避免依赖 DBMS_METADATA（不同环境兼容性差），改为优先使用达梦数据字典视图查询对象定义。
 *      若某类对象在当前环境无法通过字典视图获取，则返回占位 DDL（包含错误信息），保证导出任务不中断。
 */
public class DmDdlResolver {

    public String resolveDdl(Connection conn, String schema, String type, String name) {
        String t = safe(type).toUpperCase(Locale.ROOT);

        try {
            if ("VIEW".equals(t)) {
                return ddlForView(conn, schema, name);
            }
            if ("TRIGGER".equals(t)) {
                return ddlForTrigger(conn, schema, name);
            }
            if ("PROCEDURE".equals(t) || "FUNCTION".equals(t)) {
                return ddlForRoutine(conn, schema, t, name);
            }
            if ("SEQUENCE".equals(t)) {
                return ddlForSequence(conn, schema, name);
            }
            if ("SYNONYM".equals(t)) {
                return ddlForSynonym(conn, schema, name);
            }
            if ("TABLE".equals(t)) {
                // 说明：表的精确 DDL 由 DbDdlExporter（JDBC 元数据）负责，这里给一个简短占位，避免重复导出。
                return "-- mock-export\n-- TABLE DDL 已由 <table>.ddl.sql 输出（JDBC 元数据生成）。\n";
            }
        } catch (Exception e) {
            DeployLogWebSocket.sendLog("[mock-export] 解析 DDL 失败 schema=" + schema + " type=" + t + " name=" + name + " err=" + e.getMessage());
            return placeholder(schema, t, name, e.getMessage());
        }

        // 兜底：未知类型
        return placeholder(schema, t, name, "不支持的对象类型或无可用字典视图解析");
    }

    private String ddlForView(Connection conn, String schema, String viewName) throws Exception {
        // 说明：不同版本可能字段名不同，这里优先 ALL_VIEWS.TEXT，失败可再扩展字段候选。
        String sql = "SELECT TEXT FROM ALL_VIEWS WHERE OWNER = ? AND VIEW_NAME = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, viewName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String text = rs.getString(1);
                    if (text == null) text = "";
                    return "-- mock-export\nCREATE OR REPLACE VIEW " + schema + "." + viewName + " AS\n" + text + "\n;\n";
                }
            }
        }
        return placeholder(schema, "VIEW", viewName, "ALL_VIEWS 未返回 TEXT");
    }

    private String ddlForTrigger(Connection conn, String schema, String triggerName) throws Exception {
        // 说明：达梦触发器体一般在 ALL_TRIGGERS.TRIGGER_BODY；头部信息可按需补齐。
        String sql = "SELECT TRIGGER_BODY FROM ALL_TRIGGERS WHERE OWNER = ? AND TRIGGER_NAME = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, triggerName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String body = rs.getString(1);
                    if (body == null) body = "";
                    return "-- mock-export\n-- TRIGGER BODY (可能需要结合触发时机/事件/表名信息手工完善)\n" + body + "\n";
                }
            }
        }
        return placeholder(schema, "TRIGGER", triggerName, "ALL_TRIGGERS 未返回 TRIGGER_BODY");
    }

    private String ddlForRoutine(Connection conn, String schema, String routineType, String name) throws Exception {
        // 说明：常见做法是拼 ALL_SOURCE（或 USER_SOURCE）文本，这里尝试 ALL_SOURCE。
        String sql = "SELECT TEXT FROM ALL_SOURCE WHERE OWNER = ? AND NAME = ? AND TYPE = ? ORDER BY LINE";
        StringBuilder sb = new StringBuilder();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, name);
            ps.setString(3, routineType);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String line = rs.getString(1);
                    if (line != null) sb.append(line);
                }
            }
        }
        if (sb.length() == 0) {
            return placeholder(schema, routineType, name, "ALL_SOURCE 未返回源码文本");
        }
        return "-- mock-export\n" + sb + "\n";
    }

    private String ddlForSequence(Connection conn, String schema, String seqName) throws Exception {
        // 说明：尝试从 ALL_SEQUENCES 读取关键属性并拼装 CREATE SEQUENCE（字段在不同版本可能差异，失败则占位）。
        String sql = "SELECT MIN_VALUE, MAX_VALUE, INCREMENT_BY, CYCLE_FLAG, ORDER_FLAG, CACHE_SIZE, LAST_NUMBER FROM ALL_SEQUENCES WHERE SEQUENCE_OWNER = ? AND SEQUENCE_NAME = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, seqName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String min = rs.getString("MIN_VALUE");
                    String max = rs.getString("MAX_VALUE");
                    String inc = rs.getString("INCREMENT_BY");
                    String cycle = rs.getString("CYCLE_FLAG");
                    String order = rs.getString("ORDER_FLAG");
                    String cache = rs.getString("CACHE_SIZE");
                    String last = rs.getString("LAST_NUMBER");

                    StringBuilder ddl = new StringBuilder();
                    ddl.append("-- mock-export\n");
                    ddl.append("CREATE SEQUENCE ").append(schema).append(".").append(seqName).append("\n");
                    if (inc != null) ddl.append("  INCREMENT BY ").append(inc).append("\n");
                    if (min != null) ddl.append("  MINVALUE ").append(min).append("\n");
                    if (max != null) ddl.append("  MAXVALUE ").append(max).append("\n");
                    if (last != null) ddl.append("  START WITH ").append(last).append("\n");
                    ddl.append("  ").append("Y".equalsIgnoreCase(safe(cycle)) ? "CYCLE" : "NOCYCLE").append("\n");
                    ddl.append("  ").append("Y".equalsIgnoreCase(safe(order)) ? "ORDER" : "NOORDER").append("\n");
                    if (cache != null) ddl.append("  CACHE ").append(cache).append("\n");
                    ddl.append(";\n");
                    return ddl.toString();
                }
            }
        } catch (Exception e) {
            // 说明：字段名/视图在不同 DM 版本可能不同，抛出让上层生成占位 DDL。
            throw e;
        }
        return placeholder(schema, "SEQUENCE", seqName, "ALL_SEQUENCES 未返回属性");
    }

    private String ddlForSynonym(Connection conn, String schema, String synonymName) throws Exception {
        // 说明：同义词一般在 ALL_SYNONYMS，目标对象字段可能为 TABLE_OWNER/TABLE_NAME。
        String sql = "SELECT TABLE_OWNER, TABLE_NAME FROM ALL_SYNONYMS WHERE OWNER = ? AND SYNONYM_NAME = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, synonymName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String tableOwner = rs.getString(1);
                    String tableName = rs.getString(2);
                    return "-- mock-export\nCREATE SYNONYM " + schema + "." + synonymName + " FOR " + safe(tableOwner) + "." + safe(tableName) + ";\n";
                }
            }
        }
        return placeholder(schema, "SYNONYM", synonymName, "ALL_SYNONYMS 未返回目标对象");
    }

    private String placeholder(String schema, String type, String name, String reason) {
        return "-- mock-export\n-- DDL 获取失败/不支持（不会中断导出任务）\n-- object: " + schema + "." + name + " type=" + type + "\n-- reason: " + safe(reason) + "\n";
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }
}

