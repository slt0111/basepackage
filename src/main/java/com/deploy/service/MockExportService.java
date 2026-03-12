package com.deploy.service;

import com.deploy.model.DmConnectionRequest;
import com.deploy.model.DmObjectItem;
import com.deploy.model.MockExportJobStatus;
import com.deploy.util.ExportPathUtil;
import com.deploy.websocket.DeployLogWebSocket;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 模拟数据导出服务
 * 说明：负责创建导出任务、异步执行导出、生成 zip 并提供任务状态查询。
 */
@Service
public class MockExportService {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 内存任务表
     * 说明：工具偏运维单机使用，先使用内存保存任务状态；后续如需可扩展为持久化。
     */
    private final Map<String, MockExportJobStatus> jobs = new ConcurrentHashMap<>();

    public MockExportService() {
    }

    /**
     * 创建并启动导出任务（达梦）
     * 说明：前端传入 DBA 连接信息 + schema 列表 + 对象清单；其中 TABLE 导出 DDL+XML，其余只导出 DDL。
     */
    public MockExportJobStatus createAndStartJob(DmConnectionRequest conn, List<String> schemas, List<DmObjectItem> objects) {
        String jobId = UUID.randomUUID().toString();
        MockExportJobStatus status = new MockExportJobStatus();
        status.setJobId(jobId);
        status.setStatus(MockExportJobStatus.Status.PENDING);
        status.setMessage("任务已创建，等待开始");
        status.setZipReady(false);
        status.setStartedAt(System.currentTimeMillis());
        // 说明：记录导出对象总数与初始完成数，用于前端展示进度条。
        status.setTotalObjects(objects != null ? objects.size() : 0);
        status.setCompletedObjects(0);
        jobs.put(jobId, status);

        // 说明：异步执行导出，避免 HTTP 请求超时；简单起见使用新线程。
        Thread t = new Thread(() -> runJob(jobId, conn, schemas, objects), "mock-export-" + jobId);
        t.setDaemon(true);
        t.start();

        return status;
    }

    public MockExportJobStatus getJob(String jobId) {
        return jobs.get(jobId);
    }

    private void runJob(String jobId, DmConnectionRequest conn, List<String> schemas, List<DmObjectItem> objects) {
        MockExportJobStatus status = jobs.get(jobId);
        if (status == null) {
            return;
        }
        status.setStatus(MockExportJobStatus.Status.RUNNING);
        status.setMessage("导出中...");
        DeployLogWebSocket.sendLog("[mock-export] 开始导出任务 jobId=" + jobId + " schemas=" + schemas + " objects=" + (objects != null ? objects.size() : 0));

        try {
            // 输出根目录：generated/mock-export/<jobId>/
            Path jobDir = ExportPathUtil.getMockExportBaseDir().resolve(jobId);
            Files.createDirectories(jobDir);

            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("jobId", jobId);
            manifest.put("dbType", "DM");
            manifest.put("exportedAt", LocalDateTime.now().toString());
            manifest.put("schemas", schemas);
            manifest.put("connectionStringMasked", maskConn(conn != null ? conn.getJdbcUrl() : null));
            manifest.put("objectsSelected", objects != null ? objects.size() : 0);

            // 说明：按 schema 分目录导出，满足“选择用户模式”场景；每个 schema 下表导 DDL+XML，其它对象只导 DDL。
            DmExportRunner runner = new DmExportRunner();
            List<Map<String, Object>> schemaStats = runner.export(conn, schemas, objects, jobDir, obj -> {
                // 每处理完一个对象（无论成功或失败）更新一次进度计数
                int done = status.getCompletedObjects() + 1;
                status.setCompletedObjects(done);
            });
            manifest.put("schemaStats", schemaStats);
            // 说明：写入任务级 manifest，便于用户在 zip 根目录复核导出范围。
            writeManifest(jobDir, manifest);

            // 生成 zip：mock-export-YYYYMMDD-HHMMSS.zip
            String ts = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now());
            // 说明：按用户要求，导出文件命名格式为 mockdata-时间戳.zip
            String zipName = "mockdata-" + ts + ".zip";
            Path zipPath = ExportPathUtil.getMockExportBaseDir().resolve(zipName);
            zipDirectory(jobDir, zipPath);

            // 计算导出内容摘要（模式数/对象数/表数/总行数）和文件大小
            int totalTables = 0;
            long totalRows = 0L;
            if (schemaStats != null) {
                for (Map<String, Object> s : schemaStats) {
                    Object t = s.get("tables");
                    Object r = s.get("rows");
                    if (t instanceof Number) {
                        totalTables += ((Number) t).intValue();
                    }
                    if (r instanceof Number) {
                        totalRows += ((Number) r).longValue();
                    }
                }
            }
            long sizeBytes = 0L;
            try {
                sizeBytes = Files.size(zipPath);
            } catch (Exception ignored) {
            }
            String summary = String.format("模式数: %d, 对象数: %d, 表: %d, 总行数: %d",
                    (schemas != null ? schemas.size() : 0),
                    (objects != null ? objects.size() : 0),
                    totalTables,
                    totalRows);

            status.setZipReady(true);
            status.setZipFileName(zipName);
            status.setZipFilePath(zipPath.toAbsolutePath().toString());
            status.setZipSizeBytes(sizeBytes);
            status.setSummary(summary);
            status.setStatus(MockExportJobStatus.Status.SUCCESS);
            status.setMessage("导出完成，可下载: " + zipName);
            status.setFinishedAt(System.currentTimeMillis());

            DeployLogWebSocket.sendLog("[mock-export] 导出完成 jobId=" + jobId + " zip=" + zipName);
        } catch (Exception e) {
            status.setStatus(MockExportJobStatus.Status.FAILED);
            status.setMessage("导出失败: " + e.getMessage());
            status.setFinishedAt(System.currentTimeMillis());
            DeployLogWebSocket.sendLog("[mock-export] 导出失败 jobId=" + jobId + " err=" + e.getMessage());
        }
    }

    private void writeManifest(Path outDir, Map<String, Object> manifest) throws Exception {
        Path f = outDir.resolve("manifest.json");
        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(manifest);
        Files.write(f, json.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private void zipDirectory(Path sourceDir, Path zipPath) throws Exception {
        // 说明：zip 采用可重入的覆盖写入（同名文件会被覆盖），避免重复任务污染。
        try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(zipPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING));
             ZipOutputStream zos = new ZipOutputStream(os, StandardCharsets.UTF_8)) {

            Files.walk(sourceDir)
                    .filter(p -> !Files.isDirectory(p))
                    .forEach(p -> {
                        try {
                            Path rel = sourceDir.relativize(p);
                            ZipEntry entry = new ZipEntry(rel.toString().replace("\\", "/"));
                            zos.putNextEntry(entry);
                            Files.copy(p, zos);
                            zos.closeEntry();
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    });
        }
    }

    private String maskConn(String conn) {
        if (conn == null) {
            return "";
        }
        // 说明：连接串一般不含密码，但仍做一次简单脱敏，避免用户把敏感参数拼在 URL 中。
        return conn.replaceAll("(?i)(password=)[^&;]+", "$1******");
    }
}

