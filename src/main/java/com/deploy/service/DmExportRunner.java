package com.deploy.service;

import com.deploy.model.DmConnectionRequest;
import com.deploy.model.DmObjectItem;
import com.deploy.service.export.DbDdlExporter;
import com.deploy.service.export.DbXmlDataExporter;
import com.deploy.util.DmJdbcDriverLoader;
import com.deploy.websocket.DeployLogWebSocket;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 达梦导出执行器
 * 说明：按 schema 分目录导出对象；仅 TABLE 导出数据 XML，其余对象只导出 DDL。
 */
public class DmExportRunner {

    private final DbDdlExporter ddlExporter = new DbDdlExporter();
    private final DbXmlDataExporter xmlExporter = new DbXmlDataExporter();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DmDdlResolver ddlResolver = new DmDdlResolver();

    public DmExportRunner() {
    }

    /**
     * 导出执行入口
     * @param connReq 连接信息
     * @param schemas 选中的 schema 列表
     * @param selectedObjects 选中的对象清单
     * @param jobDir 任务根目录
     * @param progressListener 进度回调（可为空），每处理完一个对象回调一次
     */
    public List<Map<String, Object>> export(DmConnectionRequest connReq,
                                            List<String> schemas,
                                            List<DmObjectItem> selectedObjects,
                                            Path jobDir,
                                            ProgressListener progressListener) throws Exception {
        if (connReq == null) throw new IllegalArgumentException("connection不能为空");
        if (schemas == null || schemas.isEmpty()) throw new IllegalArgumentException("schemas不能为空");
        if (selectedObjects == null || selectedObjects.isEmpty()) throw new IllegalArgumentException("objects不能为空");

        // 说明：导出报告（任务级）——记录成功/失败与失败详情，方便用户复核导出结果与定位问题。
        List<Map<String, Object>> objectResults = new ArrayList<>();
        int ddlSuccess = 0;
        int ddlFailed = 0;
        int dataSuccess = 0;
        int dataFailed = 0;

        // 说明：按 schema 分组对象，便于按目录输出与统计。
        Map<String, List<DmObjectItem>> bySchema = new LinkedHashMap<>();
        for (String s : schemas) {
            bySchema.put(s, new ArrayList<>());
        }
        for (DmObjectItem o : selectedObjects) {
            if (o == null) continue;
            String schema = o.getSchema();
            if (schema == null) continue;
            if (!bySchema.containsKey(schema)) continue;
            bySchema.get(schema).add(o);
        }

        try (Connection conn = open(connReq)) {
            String catalog = safe(conn.getCatalog());
            List<Map<String, Object>> stats = new ArrayList<>();

            for (Map.Entry<String, List<DmObjectItem>> e : bySchema.entrySet()) {
                String schema = e.getKey();
                List<DmObjectItem> objects = e.getValue();

                // 说明：schema 级目录，内部按对象类型再分目录（TABLE/VIEW/SEQUENCE 等）
                Path schemaDir = jobDir.resolve(schema);
                Files.createDirectories(schemaDir);

                long tableRows = 0;
                int ddlCount = 0;
                int tableCount = 0;

                DeployLogWebSocket.sendLog("[mock-export] schema=" + schema + " 选中对象数=" + objects.size());

                for (DmObjectItem obj : objects) {
                    String type = safe(obj.getType()).toUpperCase(Locale.ROOT);
                    String name = obj.getName();
                    if (name == null || name.trim().isEmpty()) continue;

                    // 说明：对象类型目录，例如 TABLE/VIEW/SEQUENCE/...
                    String typeDirName = resolveTypeDirName(type);
                    Path typeDir = schemaDir.resolve(typeDirName);
                    Files.createDirectories(typeDir);

                    Map<String, Object> objResult = new LinkedHashMap<>();
                    objResult.put("schema", schema);
                    objResult.put("type", type);
                    objResult.put("name", name);
                    objResult.put("comment", obj.getComment());  // 对象注释
                    objResult.put("ddlFile", "");   // 相对 jobDir 的路径
                    objResult.put("ddlOk", false);
                    objResult.put("ddlError", "");
                    objResult.put("dataFile", "");  // TABLE 才会有
                    objResult.put("dataOk", false);
                    objResult.put("dataError", "");
                    objResult.put("rows", 0L);

                    // 说明：单个对象 DDL 失败不应中断整个任务；失败时写入占位 DDL 并继续。
                    if ("TABLE".equals(type)) {
                        // 表：使用基于 JDBC 元数据的导出器生成唯一 DDL 文件（后续导入可直接使用）
                        try {
                            Path ddlFile = typeDir.resolve(name + ".ddl.sql");
                            ddlExporter.exportTableDdl(conn, catalog, schema, name, ddlFile);
                            ddlCount++;
                            ddlSuccess++;
                            objResult.put("ddlOk", true);
                            objResult.put("ddlFile", jobDir.relativize(ddlFile).toString().replace("\\", "/"));
                        } catch (Exception ex) {
                            DeployLogWebSocket.sendLog("[mock-export] 警告: 导出表 DDL 失败（将继续） schema=" + schema + " table=" + name + " err=" + ex.getMessage());
                            ddlFailed++;
                            objResult.put("ddlOk", false);
                            objResult.put("ddlError", safe(ex.getMessage()));
                        }
                    } else {
                        // 非表对象：通过达梦数据字典解析 DDL，输出到对应类型目录下
                        try {
                            Path ddlFile = typeDir.resolve(name + ".ddl.sql");
                            String ddl = ddlResolver.resolveDdl(conn, schema, type, name);
                            Files.write(ddlFile, ddl.getBytes(StandardCharsets.UTF_8));
                            ddlCount++;
                            ddlSuccess++;
                            objResult.put("ddlOk", true);
                            objResult.put("ddlFile", jobDir.relativize(ddlFile).toString().replace("\\", "/"));
                        } catch (Exception ex) {
                            DeployLogWebSocket.sendLog("[mock-export] 警告: 写入对象 DDL 失败（将继续） schema=" + schema + " type=" + type + " name=" + name + " err=" + ex.getMessage());
                            ddlFailed++;
                            objResult.put("ddlOk", false);
                            objResult.put("ddlError", safe(ex.getMessage()));
                        }
                    }

                    if ("TABLE".equals(type)) {
                        tableCount++;
                        // 数据（XML）：与 DDL 同级目录，仅表会生成 data.xml
                        try {
                            Path dataFile = typeDir.resolve(name + ".data.xml");
                            long rows = xmlExporter.exportTable(conn, schemaQualified(schema, name), dataFile);
                            tableRows += rows;
                            dataSuccess++;
                            objResult.put("dataOk", true);
                            objResult.put("rows", rows);
                            objResult.put("dataFile", jobDir.relativize(dataFile).toString().replace("\\", "/"));
                        } catch (Exception ex) {
                            dataFailed++;
                            objResult.put("dataOk", false);
                            objResult.put("dataError", safe(ex.getMessage()));
                            DeployLogWebSocket.sendLog("[mock-export] 警告: 导出表数据失败（将继续） schema=" + schema + " table=" + name + " err=" + ex.getMessage());
                        }
                    }

                    objectResults.add(objResult);

                    // 进度回调：通知上层“已完成一个对象”
                    if (progressListener != null) {
                        try {
                            progressListener.onObjectCompleted(obj);
                        } catch (Exception ignored) {
                            // 忽略进度回调内部异常，避免影响导出主流程
                        }
                    }
                }

                Map<String, Object> sStat = new LinkedHashMap<>();
                sStat.put("schema", schema);
                sStat.put("ddlCount", ddlCount);
                sStat.put("tables", tableCount);
                sStat.put("rows", tableRows);
                sStat.put("generatedAt", LocalDateTime.now().toString());
                stats.add(sStat);
            }

            // 任务级导出报告：成功/失败统计 + 失败详情（对象级）
            writeJobReport(jobDir, objectResults, ddlSuccess, ddlFailed, dataSuccess, dataFailed);

            return stats;
        }
    }

    /**
     * 导出进度监听器
     * 说明：用于在每处理完一个对象后通知调用方（例如更新任务状态中的 completedObjects）。
     */
    @FunctionalInterface
    public interface ProgressListener {
        void onObjectCompleted(DmObjectItem obj);
    }

    private void writeJobReport(Path jobDir,
                                List<Map<String, Object>> objectResults,
                                int ddlSuccess, int ddlFailed,
                                int dataSuccess, int dataFailed) throws Exception {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("generatedAt", LocalDateTime.now().toString());
        report.put("ddlSuccess", ddlSuccess);
        report.put("ddlFailed", ddlFailed);
        report.put("dataSuccess", dataSuccess);
        report.put("dataFailed", dataFailed);
        report.put("totalObjects", objectResults != null ? objectResults.size() : 0);
        report.put("objects", objectResults);

        // JSON：机器可读
        Path jsonPath = jobDir.resolve("export-report.json");
        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report);
        Files.write(jsonPath, json.getBytes(StandardCharsets.UTF_8));

        // TXT：人工可读（简要汇总 + 失败详情）
        StringBuilder txt = new StringBuilder();
        txt.append("模拟数据导出报告\n");
        txt.append("生成时间: ").append(report.get("generatedAt")).append("\n");
        txt.append("对象总数: ").append(report.get("totalObjects")).append("\n");
        txt.append("DDL 成功/失败: ").append(ddlSuccess).append(" / ").append(ddlFailed).append("\n");
        txt.append("数据(XML) 成功/失败: ").append(dataSuccess).append(" / ").append(dataFailed).append("\n\n");
        txt.append("失败详情（对象级）:\n");

        int failCount = 0;
        if (objectResults != null) {
            for (Map<String, Object> o : objectResults) {
                boolean ddlOk = Boolean.TRUE.equals(o.get("ddlOk"));
                boolean dataOk = Boolean.TRUE.equals(o.get("dataOk"));
                String type = safe(String.valueOf(o.get("type")));
                if (!ddlOk || ("TABLE".equalsIgnoreCase(type) && !dataOk)) {
                    failCount++;
                    txt.append("- ").append(safe(String.valueOf(o.get("schema"))))
                            .append(".").append(safe(String.valueOf(o.get("name"))))
                            .append(" (").append(type).append(")\n");
                    if (!ddlOk) {
                        txt.append("  DDL 失败: ").append(safe(String.valueOf(o.get("ddlError")))).append("\n");
                    }
                    if ("TABLE".equalsIgnoreCase(type) && !dataOk) {
                        txt.append("  数据失败: ").append(safe(String.valueOf(o.get("dataError")))).append("\n");
                    }
                }
            }
        }
        if (failCount == 0) {
            txt.append("（无）\n");
        }

        Path txtPath = jobDir.resolve("export-report.txt");
        Files.write(txtPath, txt.toString().getBytes(StandardCharsets.UTF_8));
    }

    private Connection open(DmConnectionRequest req) throws Exception {
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

    private String schemaQualified(String schema, String name) {
        // 说明：达梦按 schema 访问对象一般使用 schema.table 的形式；这里不加引号，避免引入大小写敏感问题。
        return schema + "." + name;
    }

    /**
     * 解析对象类型对应的目录名
     * 说明：schema 目录下按对象类型再分子目录，便于后续导入按类型依次处理。
     */
    private String resolveTypeDirName(String type) {
        String t = safe(type).toUpperCase(Locale.ROOT);
        if ("TABLE".equals(t)) return "TABLE";
        if ("VIEW".equals(t)) return "VIEW";
        if ("SEQUENCE".equals(t)) return "SEQUENCE";
        if ("SYNONYM".equals(t)) return "SYNONYM";
        if ("PROCEDURE".equals(t)) return "PROCEDURE";
        if ("FUNCTION".equals(t)) return "FUNCTION";
        if ("TRIGGER".equals(t)) return "TRIGGER";
        // 未知类型作为 OTHER 归类，避免打断导出流程
        return "OTHER";
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }
}

