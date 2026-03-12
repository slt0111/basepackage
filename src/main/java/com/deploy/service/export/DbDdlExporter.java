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

        DeployLogWebSocket.sendLog("[mock-export] 导出结构: " + tableName + " -> " + outputFile.getFileName());

        DatabaseMetaData meta = conn.getMetaData();
        List<String> columnLines = new ArrayList<>();

        try (ResultSet cols = meta.getColumns(catalog, schema, tableName, null)) {
            while (cols.next()) {
                String colName = cols.getString("COLUMN_NAME");
                String typeName = cols.getString("TYPE_NAME");
                int size = cols.getInt("COLUMN_SIZE");
                int scale = cols.getInt("DECIMAL_DIGITS");
                String nullable = cols.getString("IS_NULLABLE"); // YES/NO
                String def = cols.getString("COLUMN_DEF");

                StringBuilder line = new StringBuilder();
                line.append("  ").append(colName).append(" ").append(renderType(typeName, size, scale));
                if ("NO".equalsIgnoreCase(nullable)) {
                    line.append(" NOT NULL");
                }
                if (def != null && !def.trim().isEmpty()) {
                    // 说明：默认值的表达式由数据库返回，尽量原样带出，避免二次解析导致语义变化。
                    line.append(" DEFAULT ").append(def);
                }
                columnLines.add(line.toString());
            }
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

        // 索引（简单导出：非唯一/唯一）
        List<String> indexDdls = new ArrayList<>();
        try (ResultSet idx = meta.getIndexInfo(catalog, schema, tableName, false, false)) {
            while (idx.next()) {
                String indexName = idx.getString("INDEX_NAME");
                String colName = idx.getString("COLUMN_NAME");
                boolean nonUnique = idx.getBoolean("NON_UNIQUE");
                short type = idx.getShort("TYPE");
                if (indexName == null || colName == null) {
                    continue;
                }
                // 说明：跳过表统计等非索引行
                if (type == DatabaseMetaData.tableIndexStatistic) {
                    continue;
                }
                // 说明：这里做最小化导出：每列一个索引声明可能不精确（复合索引需聚合），后续可增强为按 INDEX_NAME 聚合。
                String ddl = "CREATE " + (nonUnique ? "" : "UNIQUE ") + "INDEX " + indexName + " ON " + tableName + " (" + colName + ");";
                indexDdls.add(ddl);
            }
        } catch (Exception ignored) {
            // 说明：部分驱动对索引元数据支持不完善，索引导出失败不应影响主流程。
        }

        try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(Files.newOutputStream(outputFile), StandardCharsets.UTF_8))) {
            w.write("-- mock-export ddl for table: " + tableName + "\n");
            w.write("CREATE TABLE " + tableName + " (\n");
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
            for (String ddl : indexDdls) {
                w.write(ddl);
                w.write("\n");
            }
        }
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

