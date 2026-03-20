package com.deploy.service.export;

import com.deploy.websocket.DeployLogWebSocket;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 * DDL 导出器（每表一个 .sql 文件）
 * 说明：优先走 JDBC 元数据拼装 DDL，保证无需依赖数据库客户端工具即可导出结构。
 */
public class DbDdlExporter {

    /**
     * 导出单表 DDL（CREATE TABLE + 主键 + 索引）
     *
     * @param conn 数据库连接
     * @param catalog catalog（可空）
     * @param schema schema（可空）
     * @param tableName 表名
     * @param outputFile 输出路径
     */
    public void exportTableDdl(Connection conn, String catalog, String schema, String tableName, Path outputFile) throws Exception {
        Files.createDirectories(outputFile.getParent());

        // 说明：日志中仍打印裸表名，避免过长；实际 DDL 中会使用 schema.table 形式。
        DeployLogWebSocket.sendLog("[mock-export] 导出结构: " + tableName + " -> " + outputFile.getFileName());

        DatabaseMetaData meta = conn.getMetaData();
        List<String> columnLines = new ArrayList<>();
        // 列名清单（用于判断 DEFAULT 表达式是否引用其他列）
        java.util.List<String> allColumnNames = new java.util.ArrayList<>();
        // 字段注释（col -> remark）
        java.util.Map<String, String> columnRemarks = new java.util.LinkedHashMap<>();
        // 表注释
        String tableRemark = "";

        try (ResultSet cols = meta.getColumns(catalog, schema, tableName, null)) {
            while (cols.next()) {
                String colName = cols.getString("COLUMN_NAME");
                String typeName = cols.getString("TYPE_NAME");
                int size = cols.getInt("COLUMN_SIZE");
                int scale = cols.getInt("DECIMAL_DIGITS");
                String nullable = cols.getString("IS_NULLABLE"); // YES/NO
                String def = cols.getString("COLUMN_DEF");
                String remark = cols.getString("REMARKS");

                if (colName != null && !colName.trim().isEmpty()) {
                    allColumnNames.add(colName.trim());
                }

                StringBuilder line = new StringBuilder();
                line.append("  ").append(colName).append(" ").append(renderType(typeName, size, scale));
                if ("NO".equalsIgnoreCase(nullable)) {
                    line.append(" NOT NULL");
                }
                if (def != null && !def.trim().isEmpty()) {
                    // 说明：达梦不支持在 DEFAULT 中引用其他字段表达式（如 TO_DATE(DAYN,'yyyy-mm-dd')），
                    // 这类默认值在导入时会报“DEFAULT约束表达式无效”。若检测到 DEFAULT 引用了同表其他列，
                    // 则导出为虚拟列：GENERATED ALWAYS AS (...) VIRTUAL，保证导入可重放。
                    if (isDefaultRefersOtherColumn(def, colName, allColumnNames)) {
                        line.append(" GENERATED ALWAYS AS (").append(def.trim()).append(") VIRTUAL");
                    } else {
                        // 说明：默认值的表达式由数据库返回，尽量原样带出，避免二次解析导致语义变化。
                        line.append(" DEFAULT ").append(def);
                    }
                }
                columnLines.add(line.toString());

                // 说明：字段注释通过 JDBC 元数据 REMARKS 获取；为空则忽略。
                if (colName != null && remark != null && !remark.trim().isEmpty()) {
                    columnRemarks.put(colName, remark.trim());
                }
            }
        }

        // 表注释（REMARKS）
        try (ResultSet tabs = meta.getTables(catalog, schema, tableName, new String[]{"TABLE"})) {
            if (tabs.next()) {
                String r = tabs.getString("REMARKS");
                if (r != null) tableRemark = r.trim();
            }
        } catch (Exception ignored) {
            // 说明：部分驱动不返回表 REMARKS，忽略不影响主流程。
        }

        // 主键
        List<String> pkCols = new ArrayList<>();
        String pkName = null;
        try (ResultSet pks = meta.getPrimaryKeys(catalog, schema, tableName)) {
            while (pks.next()) {
                pkCols.add(pks.getString("COLUMN_NAME"));
                if (pkName == null) {
                    pkName = pks.getString("PK_NAME");
                }
            }
        }

        // 索引导出：按 INDEX_NAME 聚合多行，生成复合索引 DDL
        List<String> indexDdls = new ArrayList<>();
        try (ResultSet idx = meta.getIndexInfo(catalog, schema, tableName, false, false)) {
            // 说明：先将同名索引的多行元数据聚合起来（支持多列复合索引）
            java.util.Map<String, IndexDef> indexMap = new java.util.LinkedHashMap<>();
            while (idx.next()) {
                String indexName = idx.getString("INDEX_NAME");
                String colName = idx.getString("COLUMN_NAME");
                boolean nonUnique = idx.getBoolean("NON_UNIQUE");
                short type = idx.getShort("TYPE");
                short ordinal = idx.getShort("ORDINAL_POSITION");
                if (indexName == null || colName == null) {
                    continue;
                }
                // 说明：跳过表统计等非索引行
                if (type == DatabaseMetaData.tableIndexStatistic) {
                    continue;
                }
                IndexDef def = indexMap.get(indexName);
                if (def == null) {
                    // 说明：首次遇到该索引名时创建聚合对象
                    def = new IndexDef();
                    def.name = indexName;
                    def.nonUnique = nonUnique;
                    def.columns = new java.util.ArrayList<>();
                    indexMap.put(indexName, def);
                } else {
                    // 说明：若多个元数据行对唯一性标记不一致，采用“只要有一个非唯一就视为非唯一”策略
                    def.nonUnique = def.nonUnique || nonUnique;
                }
                // 说明：保存列名及其在索引中的顺序，后续按顺序拼接
                IndexColumn ic = new IndexColumn();
                ic.name = colName;
                ic.ordinal = ordinal;
                def.columns.add(ic);
            }
            // 说明：准备主键列集合，用于后续与唯一索引列进行等价性比较（避免重复导出等价于主键的唯一索引）
            // 注意：达梦在主键约束落地为索引时，索引列顺序可能与约束定义不一致，因此这里按“列集合”比较而非严格顺序比较。
            java.util.Set<String> pkColSet = new java.util.LinkedHashSet<>();
            for (String c : pkCols) {
                if (c != null && !c.trim().isEmpty()) {
                    pkColSet.add(c.trim().toUpperCase());
                }
            }

            // 说明：按索引名遍历聚合结果，生成最终 DDL
            for (IndexDef def : indexMap.values()) {
                if (def.columns == null || def.columns.isEmpty()) {
                    continue;
                }
                // 说明：按 ORDINAL_POSITION 排序，保证复合索引列顺序正确
                def.columns.sort((a, b) -> Short.compare(a.ordinal, b.ordinal));

                // 说明：对于唯一索引，若其列集合与主键列集合完全一致，则认为是主键约束底层索引，跳过导出。
                // 例如：PK(Y0300,Y0400) 时，索引 (Y0400,Y0300) 与 (Y0300,Y0400) 都是等价冗余索引。
                if (!def.nonUnique && !pkColSet.isEmpty() && def.columns.size() == pkColSet.size()) {
                    java.util.Set<String> idxColSet = new java.util.LinkedHashSet<>();
                    for (int i = 0; i < def.columns.size(); i++) {
                        String n = def.columns.get(i) != null ? def.columns.get(i).name : null;
                        if (n != null && !n.trim().isEmpty()) {
                            idxColSet.add(n.trim().toUpperCase());
                        }
                    }
                    if (idxColSet.equals(pkColSet)) {
                        // 说明：等价于主键约束的唯一索引不再单独导出，避免导入时与主键自动索引重复。
                        continue;
                    }
                }

                StringBuilder colsBuilder = new StringBuilder();
                for (int i = 0; i < def.columns.size(); i++) {
                    if (i > 0) colsBuilder.append(",");
                    colsBuilder.append(def.columns.get(i).name);
                }
                String ddl = "CREATE " + (def.nonUnique ? "" : "UNIQUE ")
                        + "INDEX " + def.name
                        + " ON " + qualifiedTableName(schema, tableName)
                        + " (" + colsBuilder + ");";
                indexDdls.add(ddl);
            }
        } catch (Exception ignored) {
            // 说明：部分驱动对索引元数据支持不完善，索引导出失败不应影响主流程。
        }

        try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(Files.newOutputStream(outputFile), StandardCharsets.UTF_8))) {
            w.write("-- mock-export ddl for table: " + tableName + "\n");
            // 说明：CREATE TABLE 使用 schema.table 全名，导入时可直接按原样执行，不依赖当前会话的缺省 schema。
            w.write("CREATE TABLE " + qualifiedTableName(schema, tableName) + " (\n");
            for (int i = 0; i < columnLines.size(); i++) {
                w.write(columnLines.get(i));
                if (i < columnLines.size() - 1 || !pkCols.isEmpty()) {
                    w.write(",");
                }
                w.write("\n");
            }
            if (!pkCols.isEmpty()) {
                w.write("  CONSTRAINT " + (pkName != null ? pkName : ("PK_" + tableName)) + " PRIMARY KEY (" + String.join(", ", pkCols) + ")\n");
            }
            w.write(");\n");
            w.write("\n");

            // 表/字段注释
            // 说明：以 COMMENT ON 语句形式导出，便于导入端直接执行。
            if (tableRemark != null && !tableRemark.trim().isEmpty()) {
                w.write("COMMENT ON TABLE " + qualifiedTableName(schema, tableName) + " IS '" + escapeSqlLiteral(tableRemark) + "';\n");
            }
            if (columnRemarks != null && !columnRemarks.isEmpty()) {
                for (java.util.Map.Entry<String, String> e : columnRemarks.entrySet()) {
                    if (e == null) continue;
                    String col = e.getKey();
                    String rem = e.getValue();
                    if (col == null || rem == null || rem.trim().isEmpty()) continue;
                    w.write("COMMENT ON COLUMN " + qualifiedTableName(schema, tableName) + "." + col + " IS '" + escapeSqlLiteral(rem) + "';\n");
                }
            }
            if ((tableRemark != null && !tableRemark.trim().isEmpty()) || (columnRemarks != null && !columnRemarks.isEmpty())) {
                w.write("\n");
            }

            for (String ddl : indexDdls) {
                w.write(ddl);
                w.write("\n");
            }
        }
    }

    /**
     * 生成 schema.table 形式的完整表名
     * 说明：当 schema 为空时仅返回表名本身，避免引入多余的前缀。
     */
    private String qualifiedTableName(String schema, String tableName) {
        if (schema == null || schema.trim().isEmpty()) {
            return tableName;
        }
        return schema + "." + tableName;
    }

    /**
     * 判断 DEFAULT 表达式是否引用了同表其他列
     * 说明：用于将 “DEFAULT 引用列表达式” 改写为达梦可用的虚拟列写法。
     */
    private boolean isDefaultRefersOtherColumn(String def, String selfCol, java.util.List<String> allCols) {
        if (def == null) return false;
        String expr = def.trim();
        if (expr.isEmpty()) return false;
        if (allCols == null || allCols.isEmpty()) return false;

        String self = selfCol == null ? "" : selfCol.trim();
        String upperExpr = expr.toUpperCase();
        for (String c : allCols) {
            if (c == null) continue;
            String col = c.trim();
            if (col.isEmpty()) continue;
            if (!self.isEmpty() && col.equalsIgnoreCase(self)) continue;

            // 说明：按“标识符边界”做一次粗略匹配，避免把列名当作普通字符串子串误匹配。
            // 匹配形式：非字母数字下划线 + COL + 非字母数字下划线（或表达式起止）
            String u = col.toUpperCase();
            if (upperExpr.matches(".*(^|[^A-Z0-9_])" + java.util.regex.Pattern.quote(u) + "([^A-Z0-9_]|$).*")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 转义 SQL 字符串字面量中的单引号
     * 说明：COMMENT ON ... IS 'xxx' 中单引号需要写成两个单引号。
     */
    private String escapeSqlLiteral(String s) {
        if (s == null) return "";
        return s.replace("'", "''");
    }

    /**
     * 内部类：索引定义聚合模型
     * 说明：用于在导出阶段按索引名聚合多行元数据，支持复合索引。
     */
    private static class IndexDef {
        /** 索引名 */
        String name;
        /** 是否非唯一（true 表示非唯一，false 表示唯一索引） */
        boolean nonUnique;
        /** 索引列列表（包含列名及其顺序） */
        java.util.List<IndexColumn> columns;
    }

    /**
     * 内部类：索引列模型
     * 说明：保存单个索引列的名称及在索引中的顺序。
     */
    private static class IndexColumn {
        /** 列名 */
        String name;
        /** 在索引中的序号（ORDINAL_POSITION） */
        short ordinal;
    }

    private String renderType(String typeName, int size, int scale) {
        if (typeName == null) {
            return "VARCHAR(255)";
        }
        String t = typeName.trim();
        String upper = t.toUpperCase();

        // 说明：对常见“需要长度/精度”的类型补齐括号；其他类型尽量原样输出。
        if (upper.contains("CHAR") || upper.contains("VARCHAR")) {
            return t + "(" + (size > 0 ? size : 255) + ")";
        }
        if (upper.contains("DECIMAL") || upper.contains("NUMBER") || upper.contains("NUMERIC")) {
            if (size > 0 && scale >= 0) {
                return t + "(" + size + "," + scale + ")";
            }
        }
        return t;
    }
}

