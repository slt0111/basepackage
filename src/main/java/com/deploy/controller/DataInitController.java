package com.deploy.controller;

import com.deploy.model.DeployConfig;
import com.deploy.service.DataInitService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 数据初始化控制器
 * 说明：提供脚本预览、脚本下载、在线执行三类接口。
 */
@RestController
@RequestMapping("/api/deploy/data-init")
@CrossOrigin(origins = "*")
public class DataInitController {

    private final DataInitService dataInitService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DataInitController(DataInitService dataInitService) {
        this.dataInitService = dataInitService;
    }

    /**
     * 预览初始化脚本（参数替换后）
     */
    @PostMapping("/preview")
    public ResponseEntity<Map<String, Object>> preview(@RequestBody Map<String, Object> req) {
        Map<String, Object> result = new HashMap<>();
        try {
            DeployConfig config = parseDeployConfig(req.get("deployConfig"));
            String app = req.get("app") == null ? "unified" : String.valueOf(req.get("app"));
            @SuppressWarnings("unchecked")
            Map<String, String> initParams = (Map<String, String>) req.get("initParams");

            Map<String, Object> data = dataInitService.preview(config, app, initParams);
            result.put("success", true);
            result.putAll(data);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "生成初始化脚本失败: " + e.getMessage());
            return ResponseEntity.ok(result);
        }
    }

    /**
     * 下载初始化脚本（合并文本）
     */
    @PostMapping("/download")
    public ResponseEntity<byte[]> download(@RequestBody Map<String, Object> req) {
        try {
            DeployConfig config = parseDeployConfig(req.get("deployConfig"));
            String app = req.get("app") == null ? "unified" : String.valueOf(req.get("app"));
            @SuppressWarnings("unchecked")
            Map<String, String> initParams = (Map<String, String>) req.get("initParams");

            Map<String, Object> data = dataInitService.preview(config, app, initParams);
            String merged = String.valueOf(data.getOrDefault("mergedScript", ""));
            byte[] bytes = merged.getBytes(StandardCharsets.UTF_8);
            String fileName = "data-init-" + ("cadre".equalsIgnoreCase(app) ? "cadre" : "unified") + ".sql";

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                    .contentType(MediaType.TEXT_PLAIN)
                    .contentLength(bytes.length)
                    .body(bytes);
        } catch (Exception e) {
            byte[] bytes = ("下载初始化脚本失败: " + e.getMessage()).getBytes(StandardCharsets.UTF_8);
            return ResponseEntity.status(500).contentType(MediaType.TEXT_PLAIN).body(bytes);
        }
    }

    /**
     * 在线执行初始化脚本
     */
    @PostMapping("/execute")
    public ResponseEntity<Map<String, Object>> execute(@RequestBody Map<String, Object> req) {
        Map<String, Object> result = new HashMap<>();
        try {
            DeployConfig config = parseDeployConfig(req.get("deployConfig"));
            String app = req.get("app") == null ? "unified" : String.valueOf(req.get("app"));
            @SuppressWarnings("unchecked")
            Map<String, String> initParams = (Map<String, String>) req.get("initParams");

            Map<String, Object> executeResult = dataInitService.execute(config, app, initParams);
            result.put("success", true);
            result.put("message", "数据初始化执行完成");
            result.putAll(executeResult);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "数据初始化执行失败: " + e.getMessage());
            return ResponseEntity.ok(result);
        }
    }

    /**
     * 将请求中的 deployConfig 转换为 DeployConfig 对象
     */
    private DeployConfig parseDeployConfig(Object configObj) {
        if (configObj == null) {
            return new DeployConfig();
        }
        return objectMapper.convertValue(configObj, DeployConfig.class);
    }
}
