package com.deploy.service.export;

import com.deploy.websocket.DeployLogWebSocket;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;

/**
 * 数据 XML 导出器（每表一个 XML 文件）
 * 说明：以流式方式读取 ResultSet 并写入 XML，尽量避免大表导出时占用过多内存。
 */
public class DbXmlDataExporter {

    /**
     * 导出单表数据到 XML
     *
     * @param conn 数据库连接
     * @param tableName 表名（已按数据库要求完成必要的大小写/引用规则由上层保障）
     * @param outputFile 输出文件路径
     * @return 导出的行数（用于 manifest 统计）
     */
    public long exportTable(Connection conn, String tableName, Path outputFile) throws Exception {
        Files.createDirectories(outputFile.getParent());

        String sql = "SELECT * FROM " + tableName;
        DeployLogWebSocket.sendLog("[mock-export] 导出数据: " + tableName + " -> " + outputFile.getFileName());

        long rows = 0;
        try (Statement st = conn.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            // 说明：对部分驱动设置 fetchSize 有助于流式拉取；若驱动不支持也不会影响功能。
            try {
                st.setFetchSize(500);
            } catch (Exception ignored) {
            }

            try (ResultSet rs = st.executeQuery(sql);
                 BufferedWriter w = new BufferedWriter(new OutputStreamWriter(Files.newOutputStream(outputFile), StandardCharsets.UTF_8))) {

                ResultSetMetaData md = rs.getMetaData();
                int colCount = md.getColumnCount();

                w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
                w.write("<table name=\"" + XmlValueWriter.escapeXml(tableName) + "\">\n");

                while (rs.next()) {
                    rows++;
                    w.write("  <row>\n");
                    for (int i = 1; i <= colCount; i++) {
                        String colName = md.getColumnLabel(i);
                        String jdbcTypeName = safe(md.getColumnTypeName(i));

                        Object value = rs.getObject(i);
                        if (value == null) {
                            w.write("    <col name=\"" + XmlValueWriter.escapeXml(colName) + "\" jdbcType=\"" + XmlValueWriter.escapeXml(jdbcTypeName) + "\" isNull=\"true\"/>\n");
                            continue;
                        }

                        boolean isBinary = value instanceof byte[] || value instanceof java.sql.Blob;
                        w.write("    <col name=\"" + XmlValueWriter.escapeXml(colName) + "\" jdbcType=\"" + XmlValueWriter.escapeXml(jdbcTypeName) + "\"");
                        if (isBinary) {
                            w.write(" encoding=\"base64\"");
                        }
                        w.write(">");

                        // 说明：CLOB 采用 CDATA，其他字符串走转义；二进制走 base64。
                        XmlValueWriter.writeValueContent(w, value);

                        w.write("</col>\n");
                    }
                    w.write("  </row>\n");
                }

                w.write("</table>\n");
            }
        }

        return rows;
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }
}

