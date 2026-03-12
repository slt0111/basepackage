package com.deploy.controller;

import com.deploy.model.GlobalSettings;
import com.deploy.service.GlobalSettingsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 全局设置控制器
 * 说明：提供“类似产品的全局设置”能力，用于覆盖系统内置固定值（端口/默认中间件/连接串模板等）。
 */
@RestController
@RequestMapping("/api/settings")
@CrossOrigin(origins = "*")
public class SettingsController {

    @Autowired
    private GlobalSettingsService globalSettingsService;

    /**
     * 获取全局设置（若未配置则返回内置默认值）
     */
    @GetMapping("/global")
    public ResponseEntity<Map<String, Object>> getGlobalSettings() {
        Map<String, Object> result = new HashMap<>();
        try {
            GlobalSettings settings = globalSettingsService.getSettings();
            result.put("success", true);
            result.put("settings", settings);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "获取全局设置失败: " + e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * 保存全局设置（持久化到本地 JSON 文件）
     */
    @PostMapping("/global")
    public ResponseEntity<Map<String, Object>> saveGlobalSettings(@RequestBody GlobalSettings settings) {
        Map<String, Object> result = new HashMap<>();
        try {
            GlobalSettings saved = globalSettingsService.saveSettings(settings);
            result.put("success", true);
            result.put("settings", saved);
            result.put("message", "全局设置保存成功");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "保存全局设置失败: " + e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }
}

