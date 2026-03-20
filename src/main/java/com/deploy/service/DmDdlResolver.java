package com.deploy.service;

import com.deploy.websocket.DeployLogWebSocket;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
        // 说明：尽量从 ALL_TRIGGERS 还原完整的 CREATE TRIGGER 语句（包含头部 + BODY），
        // 字段名在不同达梦版本可能略有差异，如获取失败则退化为占位 DDL。
        String sql = "SELECT TRIGGER_TYPE, TRIGGERING_EVENT, TABLE_OWNER, TABLE_NAME, WHEN_CLAUSE, TRIGGER_BODY " +
                "FROM ALL_TRIGGERS WHERE OWNER = ? AND TRIGGER_NAME = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, triggerName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String trigType = safe(rs.getString("TRIGGER_TYPE"));          // BEFORE/AFTER/INSTEAD OF ...
                    String event = safe(rs.getString("TRIGGERING_EVENT"));         // INSERT/UPDATE/DELETE 或组合
                    String tableOwner = safe(rs.getString("TABLE_OWNER"));
                    String tableName = safe(rs.getString("TABLE_NAME"));
                    String whenClause = rs.getString("WHEN_CLAUSE");
                    String body = rs.getString("TRIGGER_BODY");
                    if (body == null) body = "";

                    StringBuilder ddl = new StringBuilder();
                    ddl.append("-- mock-export\n");
                    ddl.append("CREATE OR REPLACE TRIGGER ").append(schema).append(".").append(triggerName).append("\n");
                    if (!trigType.isEmpty()) {
                        ddl.append("  ").append(trigType).append(" ");
                    }
                    if (!event.isEmpty()) {
                        ddl.append(event).append("\n");
                    }
                    if (!tableOwner.isEmpty() && !tableName.isEmpty()) {
                        ddl.append("  ON ").append(tableOwner).append(".").append(tableName).append("\n");
                    }
                    if (whenClause != null && !whenClause.trim().isEmpty()) {
                        ddl.append("  WHEN (").append(whenClause.trim()).append(")\n");
                    }
                    ddl.append("BEGIN\n");
                    ddl.append(body.trim()).append("\n");
                    ddl.append("END;\n");
                    // 说明：追加 "/" 作为脚本分隔符，便于导入端将整个 PL/SQL 块作为单条语句执行。
                    ddl.append("/\n");
                    return ddl.toString();
                }
            }
        } catch (Exception e) {
            // 说明：字段名/视图在不同 DM 版本可能不同，抛出给上层生成占位 DDL。
            throw e;
        }
        return placeholder(schema, "TRIGGER", triggerName, "ALL_TRIGGERS 未返回触发器定义");
    }

    private String ddlForRoutine(Connection conn, String schema, String routineType, String name) throws Exception {
        // 说明：常见做法是拼 ALL_SOURCE（或 USER_SOURCE）文本，这里优先按 routineType 精确查询；
        // 但在部分达梦版本/兼容模式下，ALL_SOURCE.TYPE 可能出现归类差异（例如函数以 PROCEDURE 形式存储），因此提供兜底查询。
        String ddl = readRoutineSource(conn, schema, name, routineType);
        if (ddl != null && !ddl.trim().isEmpty()) {
            // 说明：过程/函数的源码可能未带 schema，这里对 CREATE 头部做一次补全，避免导入时落到默认 schema。
            String qualified = qualifyRoutineCreateHeader(schema, ddl);
            // 说明：追加 "/" 作为脚本分隔符，便于导入端将整个 PL/SQL 块作为单条语句执行。
            return "-- mock-export\n" + qualified + "\n/\n";
        }

        // 兜底：尝试用另一种 TYPE 再查一次，提升命中率
        String alt = "FUNCTION".equalsIgnoreCase(routineType) ? "PROCEDURE" : "FUNCTION";
        String ddl2 = readRoutineSource(conn, schema, name, alt);
        if (ddl2 != null && !ddl2.trim().isEmpty()) {
            String qualified = qualifyRoutineCreateHeader(schema, ddl2);
            return "-- mock-export\n" + qualified + "\n/\n";
        }

        // 最后兜底：不带 TYPE 条件查询（若 ALL_SOURCE 存在多条类型记录，按 LINE 拼接）
        String ddl3 = readRoutineSourceNoType(conn, schema, name);
        if (ddl3 != null && !ddl3.trim().isEmpty()) {
            String qualified = qualifyRoutineCreateHeader(schema, ddl3);
            return "-- mock-export\n" + qualified + "\n/\n";
        }

        return placeholder(schema, routineType, name, "ALL_SOURCE 未返回源码文本");
    }

    /**
     * 为过程/函数 DDL 的 CREATE 头部补充 schema 前缀
     * 说明：
     * - 导出的 ALL_SOURCE 源码里常见为 CREATE [OR REPLACE] PROCEDURE "NAME" / FUNCTION "NAME"（不带 schema）；
     * - 在导入时若不带 schema，可能落到默认 schema，导致对象归属不正确或执行失败；
     * - 这里仅在“对象名不包含点号”时补全为 schema."NAME"；若源码已带 schema 则保持原样。
     */
    private String qualifyRoutineCreateHeader(String schema, String ddl) {
        if (ddl == null) return null;
        String s = ddl;
        String sch = safe(schema).trim();
        if (sch.isEmpty()) return s;

        // 匹配 CREATE [OR REPLACE] (PROCEDURE|FUNCTION) <name>
        Pattern p = Pattern.compile("(?is)\\bCREATE\\s+(OR\\s+REPLACE\\s+)?(PROCEDURE|FUNCTION)\\s+([^\\s(]+)");
        Matcher m = p.matcher(s);
        if (!m.find()) {
            return s;
        }
        String nameToken = m.group(3);
        if (nameToken == null) return s;
        String nt = nameToken.trim();
        // 已带 schema（含点号）则不处理
        if (nt.indexOf('.') >= 0) {
            return s;
        }

        String replace = "CREATE OR REPLACE " + m.group(2).toUpperCase(Locale.ROOT) + " " + sch + "." + nt;
        return m.replaceFirst(replace);
    }

    /**
     * 读取过程/函数源码（按 TYPE 精确匹配）
     * 说明：返回拼接后的源码文本；未命中返回空字符串。
     */
    private String readRoutineSource(Connection conn, String schema, String name, String type) throws Exception {
        String sql = "SELECT TEXT FROM ALL_SOURCE WHERE OWNER = ? AND NAME = ? AND TYPE = ? ORDER BY LINE";
        StringBuilder sb = new StringBuilder();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, name);
            ps.setString(3, type);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String line = rs.getString(1);
                    if (line != null) sb.append(line);
                }
            }
        }
        return sb.toString();
    }

    /**
     * 读取过程/函数源码（不限制 TYPE）
     * 说明：用于兼容 ALL_SOURCE.TYPE 归类异常的环境；可能返回混合类型文本，但比“完全取不到”更可用。
     */
    private String readRoutineSourceNoType(Connection conn, String schema, String name) throws Exception {
        String sql = "SELECT TEXT FROM ALL_SOURCE WHERE OWNER = ? AND NAME = ? ORDER BY LINE";
        StringBuilder sb = new StringBuilder();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String line = rs.getString(1);
                    if (line != null) sb.append(line);
                }
            }
        }
        return sb.toString();
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

