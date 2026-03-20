package com.deploy.service;

import com.deploy.model.DmConnectionRequest;
import com.deploy.model.DmObjectItem;
import com.deploy.model.ImportOptions;
import com.deploy.model.MockImportJobStatus;
import com.deploy.util.ExportPathUtil;
import com.deploy.websocket.DeployLogWebSocket;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 模拟数据导入服务
 * 说明：负责上传 zip、预览包内容、创建导入任务并异步执行 DmImportRunner，维护任务状态。
 */
@Service
public class MockImportService {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, MockImportJobStatus> jobs = new ConcurrentHashMap<>();

    private static final String UPLOADS_DIR = "uploads";

    /**
     * 上传导入包 zip，保存到 generated/mock-import/uploads/<fileId>.zip
     *
     * @param file 上传文件（mockdata-*.zip）
     * @return fileId 用于后续预览与启动导入
     */
    public String uploadZip(MultipartFile file) throws Exception {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择要上传的 zip 文件");
        }
        String originalName = file.getOriginalFilename();
        if (originalName == null || !originalName.toLowerCase().endsWith(".zip")) {
            throw new IllegalArgumentException("仅支持 .zip 文件");
        }
        String fileId = UUID.randomUUID().toString();
        Path baseDir = ExportPathUtil.getMockImportBaseDir();
        Path uploadsDir = baseDir.resolve(UPLOADS_DIR);
        Files.createDirectories(uploadsDir);
        Path dest = uploadsDir.resolve(fileId + ".zip");
        file.transferTo(dest.toFile());
        return fileId;
    }

    /**
     * 预览 zip 内容：从包内读取 manifest.json 与 export-report.json，返回 schemas 与按 schema 分组的对象列表
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> preview(String fileId) throws Exception {
        Path zipPath = resolveZipPath(fileId);
        if (!Files.exists(zipPath)) {
            throw new IllegalArgumentException("文件不存在或已过期: " + fileId);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        List<String> schemas = new ArrayList<>();
        Map<String, List<Map<String, Object>>> objectsBySchema = new LinkedHashMap<>();

        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipPath), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            byte[] manifestJson = null;
            byte[] reportJson = null;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName().replace("\\", "/");
                if (entry.isDirectory()) continue;
                if (name.equals("manifest.json")) {
                    manifestJson = readAll(zis);
                } else if (name.equals("export-report.json")) {
                    reportJson = readAll(zis);
                }
                zis.closeEntry();
            }

            if (manifestJson != null) {
                Map<String, Object> manifest = objectMapper.readValue(manifestJson, Map.class);
                Object s = manifest.get("schemas");
                if (s instanceof List) {
                    for (Object v : (List<?>) s) {
                        if (v != null) schemas.add(String.valueOf(v));
                    }
                }
            }
            if (schemas.isEmpty() && reportJson != null) {
                Map<String, Object> report = objectMapper.readValue(reportJson, Map.class);
                List<Map<String, Object>> objs = (List<Map<String, Object>>) report.get("objects");
                if (objs != null) {
                    Set<String> seen = new LinkedHashSet<>();
                    for (Map<String, Object> o : objs) {
                        String schema = String.valueOf(o.get("schema"));
                        if (schema != null && !seen.contains(schema)) {
                            seen.add(schema);
                            schemas.add(schema);
                        }
                    }
                }
            }

            if (reportJson != null) {
                Map<String, Object> report = objectMapper.readValue(reportJson, Map.class);
                List<Map<String, Object>> objs = (List<Map<String, Object>>) report.get("objects");
                if (objs != null) {
                    for (Map<String, Object> o : objs) {
                        String schema = String.valueOf(o.get("schema"));
                        if (schema == null) schema = "";
                        objectsBySchema.computeIfAbsent(schema, k -> new ArrayList<>()).add(o);
                    }
                }
            }
        }

        out.put("schemas", schemas);
        out.put("objectsBySchema", objectsBySchema);
        return out;
    }

    /**
     * 创建并启动导入任务：解压 zip 到 job 目录，异步执行 DmImportRunner
     */
    public MockImportJobStatus createAndStartJob(DmConnectionRequest connection, String fileId, ImportOptions options) throws Exception {
        Path zipPath = resolveZipPath(fileId);
        if (!Files.exists(zipPath)) {
            throw new IllegalArgumentException("上传文件不存在或已过期: " + fileId);
        }
        if (options.getSchemas() == null || options.getSchemas().isEmpty()) {
            throw new IllegalArgumentException("请至少选择一个要导入的 schema");
        }

        String jobId = UUID.randomUUID().toString();
        MockImportJobStatus status = new MockImportJobStatus();
        status.setJobId(jobId);
        status.setStatus(MockImportJobStatus.Status.PENDING);
        status.setMessage("任务已创建，等待开始");
        status.setStartedAt(System.currentTimeMillis());
        status.setZipFileName(zipPath.getFileName().toString());
        jobs.put(jobId, status);

        Path baseDir = ExportPathUtil.getMockImportBaseDir();
        Path jobDir = baseDir.resolve(jobId);
        Files.createDirectories(jobDir);
        unzip(zipPath, jobDir);

        int total = countObjectsToImport(jobDir, options);
        status.setTotalObjects(total);
        status.setCompletedObjects(0);

        Thread t = new Thread(() -> runJob(jobId, connection, jobDir, options), "mock-import-" + jobId);
        t.setDaemon(true);
        t.start();

        return status;
    }

    public MockImportJobStatus getJob(String jobId) {
        return jobs.get(jobId);
    }

    /**
     * 读取导入报告 JSON（import-report.json）
     * 说明：供前端页面展示导入结果清单与失败明细。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> readImportReportJson(String jobId) throws Exception {
        Path json = resolveImportReportFile(jobId, "json");
        if (!Files.exists(json)) {
            throw new IllegalArgumentException("导入报告不存在（任务可能未完成）: " + jobId);
        }
        return objectMapper.readValue(Files.readAllBytes(json), Map.class);
    }

    /**
     * 定位导入报告文件路径
     * @param format json 或 txt
     */
    public Path resolveImportReportFile(String jobId, String format) {
        String fmt = (format == null || format.trim().isEmpty()) ? "json" : format.trim().toLowerCase(Locale.ROOT);
        String fileName = "import-report." + ("txt".equals(fmt) ? "txt" : "json");
        Path baseDir = ExportPathUtil.getMockImportBaseDir();
        return baseDir.resolve(jobId).resolve(fileName);
    }

    private void runJob(String jobId, DmConnectionRequest conn, Path jobDir, ImportOptions options) {
        MockImportJobStatus status = jobs.get(jobId);
        if (status == null) return;
        status.setStatus(MockImportJobStatus.Status.RUNNING);
        status.setMessage("导入中...");
        DeployLogWebSocket.sendLog("[mock-import] 开始导入 jobId=" + jobId);

        try {
            DmImportRunner runner = new DmImportRunner();
            List<Map<String, Object>> results = runner.importAll(conn, jobDir, options, (schema, type, name) -> {
                MockImportJobStatus s = jobs.get(jobId);
                if (s != null) {
                    s.setCompletedObjects(s.getCompletedObjects() + 1);
                }
            });
            int ddlOk = 0, dataOk = 0, rows = 0;
            for (Map<String, Object> r : results) {
                if (Boolean.TRUE.equals(r.get("ddlOk"))) ddlOk++;
                if (Boolean.TRUE.equals(r.get("dataOk"))) dataOk++;
                Object ro = r.get("rows");
                if (ro instanceof Number) rows += ((Number) ro).intValue();
            }
            status.setStatus(MockImportJobStatus.Status.SUCCESS);
            status.setMessage("导入完成");
            status.setFinishedAt(System.currentTimeMillis());
            status.setSummary(String.format("DDL 成功: %d, 数据成功: %d, 导入行数: %d", ddlOk, dataOk, rows));
            DeployLogWebSocket.sendLog("[mock-import] 导入完成 jobId=" + jobId + " " + status.getSummary());
        } catch (Exception e) {
            status.setStatus(MockImportJobStatus.Status.FAILED);
            status.setMessage("导入失败: " + e.getMessage());
            status.setFinishedAt(System.currentTimeMillis());
            DeployLogWebSocket.sendLog("[mock-import] 导入失败 jobId=" + jobId + " err=" + e.getMessage());
        }
    }

    private Path resolveZipPath(String fileId) {
        return ExportPathUtil.getMockImportBaseDir().resolve(UPLOADS_DIR).resolve(fileId + ".zip");
    }

    private byte[] readAll(InputStream is) throws Exception {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) {
            baos.write(buf, 0, n);
        }
        return baos.toByteArray();
    }

    private void unzip(Path zipPath, Path destDir) throws Exception {
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipPath), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName().replace("\\", "/");
                if (name.contains("..")) continue;
                Path file = destDir.resolve(name);
                if (entry.isDirectory()) {
                    Files.createDirectories(file);
                } else {
                    Files.createDirectories(file.getParent());
                    Files.copy(zis, file);
                }
                zis.closeEntry();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private int countObjectsToImport(Path jobDir, ImportOptions options) throws Exception {
        Path reportPath = jobDir.resolve("export-report.json");
        if (!Files.exists(reportPath)) return 0;
        Map<String, Object> report = objectMapper.readValue(Files.readAllBytes(reportPath), Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> objects = (List<Map<String, Object>>) report.get("objects");
        if (objects == null) return 0;
        Set<String> schemaSet = new HashSet<>(options.getSchemas());
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> o : objects) {
            String schema = String.valueOf(o.get("schema"));
            if (schemaSet.contains(schema)) filtered.add(o);
        }
        if (options.getObjectSelections() != null && !options.getObjectSelections().isEmpty()) {
            Set<String> keys = new HashSet<>();
            for (DmObjectItem item : options.getObjectSelections()) {
                if (item != null && item.getSchema() != null && item.getName() != null && item.getType() != null) {
                    keys.add(item.getSchema() + "::" + item.getType() + "::" + item.getName());
                }
            }
            if (!keys.isEmpty()) {
                filtered.removeIf(o -> !keys.contains(String.valueOf(o.get("schema")) + "::" + String.valueOf(o.get("type")) + "::" + String.valueOf(o.get("name"))));
            }
        }
        return filtered.size();
    }
}
