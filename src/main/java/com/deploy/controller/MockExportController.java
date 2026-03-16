package com.deploy.controller;

import com.deploy.model.DmConnectionRequest;
import com.deploy.model.DmObjectItem;
import com.deploy.model.MockExportJobStatus;
import com.deploy.service.DmMetadataService;
import com.deploy.service.MockExportService;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 模拟数据导出 API 控制器
 * 说明：独立于一键部署流程，复用其配置模板（configs/*.json）作为数据库连接来源。
 */
@RestController
@RequestMapping("/api/mock-export")
@CrossOrigin(origins = "*")
public class MockExportController {

    private final MockExportService mockExportService;
    private final DmMetadataService dmMetadataService;

    public MockExportController(MockExportService mockExportService, DmMetadataService dmMetadataService) {
        this.mockExportService = mockExportService;
        this.dmMetadataService = dmMetadataService;
    }

    /**
     * 创建并启动导出任务
     * 入参：
     * - jdbcUrl/username/password: 达梦连接信息（DBA 用户）
     * - schemas: 选择的用户模式列表（可多选）
     * - objects: 选择的对象清单（包含 schema/name/type），其中 TABLE 会导出数据 XML，其余只导出 DDL
     */
    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> start(@RequestBody Map<String, Object> req) {
        Map<String, Object> result = new HashMap<>();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> connMap = (Map<String, Object>) req.get("connection");
            @SuppressWarnings("unchecked")
            List<String> schemas = (List<String>) req.get("schemas");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> objectsRaw = (List<Map<String, Object>>) req.get("objects");

            if (connMap == null) {
                result.put("success", false);
                result.put("message", "connection 不能为空");
                return ResponseEntity.badRequest().body(result);
            }
            if (schemas == null || schemas.isEmpty()) {
                result.put("success", false);
                result.put("message", "schemas 不能为空（至少选择一个用户模式）");
                return ResponseEntity.badRequest().body(result);
            }
            if (objectsRaw == null || objectsRaw.isEmpty()) {
                result.put("success", false);
                result.put("message", "objects 不能为空（至少选择一个导出对象）");
                return ResponseEntity.badRequest().body(result);
            }

            DmConnectionRequest conn = new DmConnectionRequest();
            conn.setJdbcUrl((String) connMap.get("jdbcUrl"));
            conn.setUsername((String) connMap.get("username"));
            conn.setPassword((String) connMap.get("password"));

            List<DmObjectItem> objects = new ArrayList<>();
            for (Map<String, Object> o : objectsRaw) {
                if (o == null) continue;
                DmObjectItem it = new DmObjectItem();
                it.setSchema((String) o.get("schema"));
                it.setName((String) o.get("name"));
                it.setType((String) o.get("type"));
                it.setComment((String) o.get("comment"));  // 读取前端传递的注释
                if (it.getSchema() != null && it.getName() != null && it.getType() != null) {
                    objects.add(it);
                }
            }
            if (objects.isEmpty()) {
                result.put("success", false);
                result.put("message", "objects 无有效条目");
                return ResponseEntity.badRequest().body(result);
            }

            MockExportJobStatus job = mockExportService.createAndStartJob(conn, schemas, objects);
            result.put("success", true);
            result.put("jobId", job.getJobId());
            result.put("message", "导出任务已启动");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "启动导出失败: " + e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * 达梦：列出用户模式（schema）列表
     */
    @PostMapping("/dm/schemas")
    public ResponseEntity<Map<String, Object>> listDmSchemas(@RequestBody DmConnectionRequest req) {
        Map<String, Object> result = new HashMap<>();
        try {
            List<String> schemas = dmMetadataService.listSchemas(req);
            result.put("success", true);
            result.put("schemas", schemas);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "获取 schema 列表失败: " + e.getMessage());
            return ResponseEntity.ok(result);
        }
    }

    /**
     * 达梦：列出指定 schema 下的对象清单
     * 入参：{ jdbcUrl, username, password, schemas: [...] }
     */
    @PostMapping("/dm/objects")
    public ResponseEntity<Map<String, Object>> listDmObjects(@RequestBody Map<String, Object> req) {
        Map<String, Object> result = new HashMap<>();
        try {
            DmConnectionRequest conn = new DmConnectionRequest();
            conn.setJdbcUrl((String) req.get("jdbcUrl"));
            conn.setUsername((String) req.get("username"));
            conn.setPassword((String) req.get("password"));
            @SuppressWarnings("unchecked")
            List<String> schemas = (List<String>) req.get("schemas");

            Map<String, List<DmObjectItem>> objects = dmMetadataService.listObjects(conn, schemas);
            result.put("success", true);
            result.put("objectsBySchema", objects);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "获取对象清单失败: " + e.getMessage());
            return ResponseEntity.ok(result);
        }
    }

    /**
     * 查询任务状态
     */
    @GetMapping("/status/{jobId}")
    public ResponseEntity<Map<String, Object>> status(@PathVariable String jobId) {
        Map<String, Object> result = new HashMap<>();
        MockExportJobStatus job = mockExportService.getJob(jobId);
        if (job == null) {
            result.put("success", false);
            result.put("message", "任务不存在: " + jobId);
            return ResponseEntity.ok(result);
        }
        result.put("success", true);
        result.put("job", job);
        return ResponseEntity.ok(result);
    }

    /**
     * 下载导出 zip
     */
    @GetMapping("/download/{jobId}")
    public ResponseEntity<?> download(@PathVariable String jobId) {
        MockExportJobStatus job = mockExportService.getJob(jobId);
        if (job == null) {
            return ResponseEntity.notFound().build();
        }
        if (!job.isZipReady() || job.getZipFilePath() == null) {
            return ResponseEntity.badRequest().body(Collections.singletonMap("message", "zip 尚未生成完成"));
        }

        try {
            // 说明：兼容 Java 8，避免使用 Path.of()
            Path zipPath = java.nio.file.Paths.get(job.getZipFilePath());
            if (!Files.exists(zipPath)) {
                return ResponseEntity.status(404).body(Collections.singletonMap("message", "zip 文件不存在"));
            }

            InputStreamResource resource = new InputStreamResource(new FileInputStream(zipPath.toFile()));
            String fileName = job.getZipFileName() != null ? job.getZipFileName() : zipPath.getFileName().toString();

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .contentLength(Files.size(zipPath))
                    .body(resource);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Collections.singletonMap("message", "下载失败: " + e.getMessage()));
        }
    }
}

