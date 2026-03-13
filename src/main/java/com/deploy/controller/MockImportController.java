package com.deploy.controller;

import com.deploy.model.DmConnectionRequest;
import com.deploy.model.DmObjectItem;
import com.deploy.model.ImportOptions;
import com.deploy.model.MockImportJobStatus;
import com.deploy.service.MockImportService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

/**
 * 模拟数据导入 API 控制器
 * 说明：提供 zip 上传、预览、启动导入、查询状态等接口，与设计文档 2026-03-13-mock-import-design 一致。
 */
@RestController
@RequestMapping("/api/mock-import")
@CrossOrigin(origins = "*")
public class MockImportController {

    private final MockImportService mockImportService;

    public MockImportController(MockImportService mockImportService) {
        this.mockImportService = mockImportService;
    }

    /**
     * 上传导入包 zip
     * 请求：multipart/form-data，字段名 file
     */
    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> upload(@RequestParam("file") MultipartFile file) {
        Map<String, Object> result = new HashMap<>();
        try {
            String fileId = mockImportService.uploadZip(file);
            result.put("success", true);
            result.put("fileId", fileId);
            result.put("message", "上传成功");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "上传失败: " + e.getMessage());
            return ResponseEntity.ok(result);
        }
    }

    /**
     * 预览 zip 内容：返回 schemas 与 objectsBySchema
     */
    @PostMapping("/preview")
    public ResponseEntity<Map<String, Object>> preview(@RequestBody Map<String, Object> req) {
        Map<String, Object> result = new HashMap<>();
        try {
            String fileId = (String) req.get("fileId");
            if (fileId == null || fileId.trim().isEmpty()) {
                result.put("success", false);
                result.put("message", "fileId 不能为空");
                return ResponseEntity.badRequest().body(result);
            }
            Map<String, Object> data = mockImportService.preview(fileId.trim());
            result.put("success", true);
            result.put("schemas", data.get("schemas"));
            result.put("objectsBySchema", data.get("objectsBySchema"));
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "预览失败: " + e.getMessage());
            return ResponseEntity.ok(result);
        }
    }

    /**
     * 启动导入任务
     * 请求体：connection, fileId, options(schemas, objectSelections, ddlMode, whenExists)
     */
    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> start(@RequestBody Map<String, Object> req) {
        Map<String, Object> result = new HashMap<>();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> connMap = (Map<String, Object>) req.get("connection");
            String fileId = (String) req.get("fileId");
            @SuppressWarnings("unchecked")
            Map<String, Object> optionsMap = (Map<String, Object>) req.get("options");

            if (connMap == null) {
                result.put("success", false);
                result.put("message", "connection 不能为空");
                return ResponseEntity.badRequest().body(result);
            }
            if (fileId == null || fileId.trim().isEmpty()) {
                result.put("success", false);
                result.put("message", "fileId 不能为空（请先上传 zip）");
                return ResponseEntity.badRequest().body(result);
            }
            if (optionsMap == null) {
                result.put("success", false);
                result.put("message", "options 不能为空");
                return ResponseEntity.badRequest().body(result);
            }

            DmConnectionRequest connection = new DmConnectionRequest();
            connection.setJdbcUrl((String) connMap.get("jdbcUrl"));
            connection.setUsername((String) connMap.get("username"));
            connection.setPassword((String) connMap.get("password"));

            ImportOptions options = new ImportOptions();
            @SuppressWarnings("unchecked")
            List<String> schemas = (List<String>) optionsMap.get("schemas");
            options.setSchemas(schemas != null ? schemas : Collections.emptyList());

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> objRaw = (List<Map<String, Object>>) optionsMap.get("objectSelections");
            if (objRaw != null && !objRaw.isEmpty()) {
                List<DmObjectItem> selections = new ArrayList<>();
                for (Map<String, Object> o : objRaw) {
                    if (o == null) continue;
                    DmObjectItem it = new DmObjectItem();
                    it.setSchema((String) o.get("schema"));
                    it.setName((String) o.get("name"));
                    it.setType((String) o.get("type"));
                    if (it.getSchema() != null && it.getName() != null && it.getType() != null) {
                        selections.add(it);
                    }
                }
                options.setObjectSelections(selections);
            }

            String ddlModeStr = (String) optionsMap.get("ddlMode");
            if (ddlModeStr != null) {
                try {
                    options.setDdlMode(ImportOptions.DdlMode.valueOf(ddlModeStr));
                } catch (Exception ignored) {
                }
            }
            String whenExistsStr = (String) optionsMap.get("whenExists");
            if (whenExistsStr != null) {
                try {
                    options.setWhenExists(ImportOptions.WhenExists.valueOf(whenExistsStr));
                } catch (Exception ignored) {
                }
            }

            MockImportJobStatus job = mockImportService.createAndStartJob(connection, fileId.trim(), options);
            result.put("success", true);
            result.put("jobId", job.getJobId());
            result.put("message", "导入任务已启动");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "启动导入失败: " + e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * 查询导入任务状态
     */
    @GetMapping("/status/{jobId}")
    public ResponseEntity<Map<String, Object>> status(@PathVariable String jobId) {
        Map<String, Object> result = new HashMap<>();
        MockImportJobStatus job = mockImportService.getJob(jobId);
        if (job == null) {
            result.put("success", false);
            result.put("message", "任务不存在: " + jobId);
            return ResponseEntity.ok(result);
        }
        result.put("success", true);
        result.put("job", job);
        return ResponseEntity.ok(result);
    }
}
