package com.deploy.service.imports;

import com.deploy.websocket.DeployLogWebSocket;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * 表数据 XML 导入器
 * 说明：解析由 DbXmlDataExporter 生成的 table 数据 XML，按批次执行 INSERT，与导出格式一一对应。
 */
public class DbXmlDataImporter {

    private static final int BATCH_SIZE = 500;

    /**
     * 将指定 XML 文件中的数据导入到目标表
     *
     * @param conn      数据库连接
     * @param schema    模式名
     * @param tableName 表名（不含 schema）
     * @param dataFile  表数据 XML 文件路径
     * @return 成功导入的行数
     */
    public long importTable(Connection conn, String schema, String tableName, Path dataFile) throws Exception {
        if (!Files.exists(dataFile)) {
            return 0;
        }
        String fullTableName = schema + "." + tableName;
        DeployLogWebSocket.sendLog("[mock-import] 导入数据: " + fullTableName + " <- " + dataFile.getFileName());

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(Files.newInputStream(dataFile));
        doc.getDocumentElement().normalize();

        Element tableEl = doc.getDocumentElement();
        NodeList rows = tableEl.getElementsByTagName("row");
        int rowCount = rows.getLength();
        if (rowCount == 0) {
            return 0;
        }

        // 说明：从第一行读取列名顺序，保证 INSERT 列顺序与 XML 一致
        Element firstRow = (Element) rows.item(0);
        NodeList cols = firstRow.getElementsByTagName("col");
        List<String> colNames = new ArrayList<>();
        for (int i = 0; i < cols.getLength(); i++) {
            Element col = (Element) cols.item(i);
            colNames.add(col.getAttribute("name"));
        }
        if (colNames.isEmpty()) {
            return 0;
        }

        StringBuilder sql = new StringBuilder("INSERT INTO ");
        sql.append(schema).append(".").append(tableName).append(" (");
        for (int i = 0; i < colNames.size(); i++) {
            if (i > 0) sql.append(",");
            sql.append(colNames.get(i));
        }
        sql.append(") VALUES (");
        for (int i = 0; i < colNames.size(); i++) {
            if (i > 0) sql.append(",");
            sql.append("?");
        }
        sql.append(")");

        long imported = 0;
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int r = 0; r < rowCount; r++) {
                Element row = (Element) rows.item(r);
                NodeList rowCols = row.getElementsByTagName("col");
                // 说明：按列名顺序设置参数，XML 中 col 可能顺序不一，需根据 name 对应到列索引
                java.util.Map<String, Object> rowValues = new java.util.LinkedHashMap<>();
                for (int i = 0; i < rowCols.getLength(); i++) {
                    Element col = (Element) rowCols.item(i);
                    String name = col.getAttribute("name");
                    String isNull = col.getAttribute("isNull");
                    if ("true".equalsIgnoreCase(isNull)) {
                        rowValues.put(name, null);
                        continue;
                    }
                    String encoding = col.getAttribute("encoding");
                    String text = getTextContent(col);
                    if ("base64".equalsIgnoreCase(encoding)) {
                        byte[] bytes = Base64.getDecoder().decode(text != null ? text.trim() : "");
                        rowValues.put(name, bytes);
                    } else {
                        rowValues.put(name, text);
                    }
                }
                for (int c = 0; c < colNames.size(); c++) {
                    String colName = colNames.get(c);
                    Object val = rowValues.get(colName);
                    if (val == null) {
                        ps.setObject(c + 1, null);
                    } else if (val instanceof byte[]) {
                        ps.setBytes(c + 1, (byte[]) val);
                    } else {
                        ps.setString(c + 1, val.toString());
                    }
                }
                ps.addBatch();
                imported++;
                if (imported % BATCH_SIZE == 0) {
                    ps.executeBatch();
                }
            }
            if (imported % BATCH_SIZE != 0) {
                ps.executeBatch();
            }
        }
        return imported;
    }

    private static String getTextContent(Element el) {
        if (el == null) return null;
        return el.getTextContent();
    }
}
