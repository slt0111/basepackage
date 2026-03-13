package com.deploy.service;

import com.deploy.model.DmConnectionRequest;
import com.deploy.model.ImportOptions;
import com.deploy.service.imports.DbXmlDataImporter;
import com.deploy.util.DmJdbcDriverLoader;
import com.deploy.websocket.DeployLogWebSocket;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 达梦导入执行器
 * 说明：读取导出包解压目录（manifest + export-report），按类型顺序执行 DDL 与表数据导入，并写导入报告。
 */
public class DmImportRunner {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final DbXmlDataImporter XML_IMPORTER = new DbXmlDataImporter();

    /** 类型执行顺序：表先建再导数据，再序列/同义词，再视图，最后过程/函数/触发器 */
    private static final List<String> TYPE_ORDER = Arrays.asList(
            "TABLE", "SEQUENCE", "SYNONYM", "VIEW", "PROCEDURE", "FUNCTION", "TRIGGER", "OTHER");

    /**
     * 导入执行入口
     *
     * @param connReq  目标库连接
     * @param unzipDir 导出 zip 解压后的根目录（含 export-report.json、schema/type/ 等）
     * @param options  导入范围与策略
     * @param listener 进度回调（每处理完一个对象调用一次，可为 null）
     * @return 对象级结果列表，用于生成 import-report
     */
    public List<Map<String, Object>> importAll(DmConnectionRequest connReq,
                                                Path unzipDir,
                                                ImportOptions options,
                                                ProgressListener listener) throws Exception {
        if (connReq == null) throw new IllegalArgumentException("connection 不能为空");
        if (unzipDir == null || !Files.isDirectory(unzipDir)) throw new IllegalArgumentException("解压目录无效");
        if (options == null) throw new IllegalArgumentException("options 不能为空");

        Path reportPath = unzipDir.resolve("export-report.json");
        if (!Files.exists(reportPath)) {
            throw new IllegalArgumentException("导出包中缺少 export-report.json，无法解析对象清单");
        }
        Map<String, Object> report = OBJECT_MAPPER.readValue(Files.readAllBytes(reportPath), Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> objects = (List<Map<String, Object>>) report.get("objects");
        if (objects == null) objects = new ArrayList<>();

        List<String> schemas = options.getSchemas() != null ? options.getSchemas() : Collections.emptyList();
        Set<String> schemaSet = new HashSet<>(schemas);
        List<Map<String, Object>> selected = objects.stream()
                .filter(o -> {
                    String schema = safeStr(o.get("schema"));
                    return schemaSet.isEmpty() || schemaSet.contains(schema);
                })
                .collect(Collectors.toList());

        if (options.getObjectSelections() != null && !options.getObjectSelections().isEmpty()) {
            Set<String> keys = new HashSet<>();
            for (com.deploy.model.DmObjectItem item : options.getObjectSelections()) {
                if (item != null && item.getSchema() != null && item.getName() != null && item.getType() != null) {
                    keys.add(item.getSchema() + "::" + item.getType().toUpperCase(Locale.ROOT) + "::" + item.getName());
                }
            }
            if (!keys.isEmpty()) {
                selected = selected.stream()
                        .filter(o -> keys.contains(safeStr(o.get("schema")) + "::" + safeStr(o.get("type")).toUpperCase(Locale.ROOT) + "::" + safeStr(o.get("name"))))
                        .collect(Collectors.toList());
            }
        }

        selected.sort((a, b) -> {
            int ia = TYPE_ORDER.indexOf(safeStr(a.get("type")).toUpperCase(Locale.ROOT));
            int ib = TYPE_ORDER.indexOf(safeStr(b.get("type")).toUpperCase(Locale.ROOT));
            if (ia < 0) ia = TYPE_ORDER.size();
            if (ib < 0) ib = TYPE_ORDER.size();
            int c = Integer.compare(ia, ib);
            if (c != 0) return c;
            int sc = safeStr(a.get("schema")).compareTo(safeStr(b.get("schema")));
            if (sc != 0) return sc;
            return safeStr(a.get("name")).compareTo(safeStr(b.get("name")));
        });

        List<Map<String, Object>> results = new ArrayList<>();
        ImportOptions.DdlMode ddlMode = options.getDdlMode() != null ? options.getDdlMode() : ImportOptions.DdlMode.STRUCTURE_AND_DATA;
        ImportOptions.WhenExists whenExists = options.getWhenExists() != null ? options.getWhenExists() : ImportOptions.WhenExists.SKIP;

        try (Connection conn = open(connReq)) {
            for (Map<String, Object> obj : selected) {
                String schema = safeStr(obj.get("schema"));
                String type = safeStr(obj.get("type")).toUpperCase(Locale.ROOT);
                String name = safeStr(obj.get("name"));
                Map<String, Object> res = new LinkedHashMap<>();
                res.put("schema", schema);
                res.put("type", type);
                res.put("name", name);
                res.put("ddlOk", false);
                res.put("ddlError", "");
                res.put("dataOk", false);
                res.put("dataError", "");
                res.put("rows", 0L);

                String typeDir = resolveTypeDirName(type);
                Path schemaDir = unzipDir.resolve(schema);
                Path typePath = schemaDir.resolve(typeDir);
                Path ddlFile = typePath.resolve(name + ".ddl.sql");
                Path dataFile = typePath.resolve(name + ".data.xml");

                boolean runDdl = (ddlMode == ImportOptions.DdlMode.STRUCTURE_AND_DATA || ddlMode == ImportOptions.DdlMode.ONLY_STRUCTURE) && Files.exists(ddlFile);
                boolean runData = (ddlMode == ImportOptions.DdlMode.STRUCTURE_AND_DATA || ddlMode == ImportOptions.DdlMode.ONLY_DATA) && "TABLE".equals(type) && Files.exists(dataFile);

                if (runDdl) {
                    if (whenExists == ImportOptions.WhenExists.DROP_AND_RECREATE) {
                        try {
                            dropObject(conn, schema, type, name);
                        } catch (Exception e) {
                            // 说明：对象不存在时 DROP 会报错，可忽略继续执行 DDL
                            DeployLogWebSocket.sendLog("[mock-import] 跳过 DROP（可能不存在）: " + schema + "." + name + " " + e.getMessage());
                        }
                    } else {
                        if (objectExists(conn, schema, type, name)) {
                            res.put("ddlOk", true);
                            res.put("ddlError", "已存在，跳过");
                            if (!runData) {
                                results.add(res);
                                if (listener != null) try { listener.onObjectCompleted(schema, type, name); } catch (Exception ignored) { }
                                continue;
                            }
                        }
                    }
                    try {
                        String ddl = new String(Files.readAllBytes(ddlFile), StandardCharsets.UTF_8).trim();
                        if (!ddl.isEmpty()) {
                            try (Statement st = conn.createStatement()) {
                                st.execute(ddl);
                            }
                        }
                        res.put("ddlOk", true);
                    } catch (Exception e) {
                        res.put("ddlError", e.getMessage());
                        DeployLogWebSocket.sendLog("[mock-import] DDL 失败: " + schema + "." + name + " " + e.getMessage());
                    }
                }

                // 说明：仅数据模式或已成功执行 DDL 时，才导入表数据；仅数据模式下若表不存在则跳过
                boolean doData = runData && (ddlMode == ImportOptions.DdlMode.ONLY_DATA ? objectExists(conn, schema, type, name) : Boolean.TRUE.equals(res.get("ddlOk")));
                if (runData && !doData && ddlMode == ImportOptions.DdlMode.ONLY_DATA) {
                    res.put("dataError", "表不存在，跳过数据导入");
                }
                if (doData) {
                    try {
                        long rows = XML_IMPORTER.importTable(conn, schema, name, dataFile);
                        res.put("dataOk", true);
                        res.put("rows", rows);
                    } catch (Exception e) {
                        res.put("dataError", e.getMessage());
                        DeployLogWebSocket.sendLog("[mock-import] 数据导入失败: " + schema + "." + name + " " + e.getMessage());
                    }
                }

                results.add(res);
                if (listener != null) {
                    try { listener.onObjectCompleted(schema, type, name); } catch (Exception ignored) { }
                }
            }

            writeImportReport(unzipDir, results);
        }
        return results;
    }

    /** 进度回调：每处理完一个对象调用，参数为 schema、type、name */
    @FunctionalInterface
    public interface ProgressListener {
        void onObjectCompleted(String schema, String type, String name);
    }

    private void dropObject(Connection conn, String schema, String type, String name) throws Exception {
        String qualified = schema + "." + name;
        try (Statement st = conn.createStatement()) {
            switch (type) {
                case "TABLE":
                    st.execute("DROP TABLE " + qualified);
                    break;
                case "VIEW":
                    st.execute("DROP VIEW " + qualified);
                    break;
                case "SEQUENCE":
                    st.execute("DROP SEQUENCE " + qualified);
                    break;
                case "SYNONYM":
                    st.execute("DROP SYNONYM " + qualified);
                    break;
                case "PROCEDURE":
                    st.execute("DROP PROCEDURE " + qualified);
                    break;
                case "FUNCTION":
                    st.execute("DROP FUNCTION " + qualified);
                    break;
                case "TRIGGER":
                    st.execute("DROP TRIGGER " + qualified);
                    break;
                default:
                    // 未知类型不执行 DROP
                    break;
            }
        }
    }

    private boolean objectExists(Connection conn, String schema, String type, String name) {
        try (Statement st = conn.createStatement()) {
            if ("TABLE".equals(type) || "VIEW".equals(type)) {
                return st.executeQuery("SELECT 1 FROM ALL_TABLES WHERE OWNER='" + schema + "' AND TABLE_NAME='" + name + "'").next();
            }
            if ("SEQUENCE".equals(type)) {
                return st.executeQuery("SELECT 1 FROM ALL_SEQUENCES WHERE SEQUENCE_OWNER='" + schema + "' AND SEQUENCE_NAME='" + name + "'").next();
            }
            // 其他类型简化：尝试 DDL 时若已存在会报错，由上层捕获
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private void writeImportReport(Path unzipDir, List<Map<String, Object>> results) throws Exception {
        int ddlOk = 0, ddlFail = 0, dataOk = 0, dataFail = 0;
        for (Map<String, Object> r : results) {
            if (Boolean.TRUE.equals(r.get("ddlOk"))) ddlOk++; else if (!"".equals(safeStr(r.get("ddlError")))) ddlFail++;
            if (Boolean.TRUE.equals(r.get("dataOk"))) dataOk++; else if (!"".equals(safeStr(r.get("dataError")))) dataFail++;
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("generatedAt", LocalDateTime.now().toString());
        report.put("ddlSuccess", ddlOk);
        report.put("ddlFailed", ddlFail);
        report.put("dataSuccess", dataOk);
        report.put("dataFailed", dataFail);
        report.put("totalObjects", results.size());
        report.put("objects", results);

        Path jsonPath = unzipDir.resolve("import-report.json");
        Files.write(jsonPath, OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(report).getBytes(StandardCharsets.UTF_8));

        StringBuilder txt = new StringBuilder();
        txt.append("模拟数据导入报告\n");
        txt.append("生成时间: ").append(report.get("generatedAt")).append("\n");
        txt.append("对象总数: ").append(report.get("totalObjects")).append("\n");
        txt.append("DDL 成功/失败: ").append(ddlOk).append(" / ").append(ddlFail).append("\n");
        txt.append("数据 成功/失败: ").append(dataOk).append(" / ").append(dataFail).append("\n\n");
        txt.append("失败详情:\n");
        for (Map<String, Object> o : results) {
            if (!Boolean.TRUE.equals(o.get("ddlOk")) || !safeStr(o.get("ddlError")).isEmpty()
                    || (!Boolean.TRUE.equals(o.get("dataOk")) && "TABLE".equals(safeStr(o.get("type"))) && !safeStr(o.get("dataError")).isEmpty())) {
                txt.append("- ").append(safeStr(o.get("schema"))).append(".").append(safeStr(o.get("name"))).append(" (").append(safeStr(o.get("type"))).append(")\n");
                if (!safeStr(o.get("ddlError")).isEmpty()) txt.append("  DDL: ").append(safeStr(o.get("ddlError"))).append("\n");
                if (!safeStr(o.get("dataError")).isEmpty()) txt.append("  数据: ").append(safeStr(o.get("dataError"))).append("\n");
            }
        }
        Files.write(unzipDir.resolve("import-report.txt"), txt.toString().getBytes(StandardCharsets.UTF_8));
    }

    private Connection open(DmConnectionRequest req) throws Exception {
        DmJdbcDriverLoader.ensureDmDriverLoaded();
        DmJdbcDriverLoader.ensureDriverRegistered(req.getJdbcUrl().trim());
        DriverManager.setLoginTimeout(15);
        Properties props = new Properties();
        props.setProperty("user", req.getUsername());
        props.setProperty("password", req.getPassword());
        return DriverManager.getConnection(req.getJdbcUrl().trim(), props);
    }

    private String resolveTypeDirName(String type) {
        String t = safeStr(type).toUpperCase(Locale.ROOT);
        if ("TABLE".equals(t)) return "TABLE";
        if ("VIEW".equals(t)) return "VIEW";
        if ("SEQUENCE".equals(t)) return "SEQUENCE";
        if ("SYNONYM".equals(t)) return "SYNONYM";
        if ("PROCEDURE".equals(t)) return "PROCEDURE";
        if ("FUNCTION".equals(t)) return "FUNCTION";
        if ("TRIGGER".equals(t)) return "TRIGGER";
        return "OTHER";
    }

    private static String safeStr(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }
}
