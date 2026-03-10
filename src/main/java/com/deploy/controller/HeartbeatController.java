package com.deploy.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

/**
 * 业务系统心跳检测控制器
 * 说明：用于后端代为请求统一支撑 / 干部应用的 heartbeat 接口，避免前端跨域限制。
 */
@RestController
@RequestMapping("/api/heartbeat")
@CrossOrigin(origins = "*")
public class HeartbeatController {

    /**
     * 检测统一支撑 / 干部应用心跳
     * 请求体示例：
     * {
     *   "unifiedUrl": "http://host:8111/tyzc-api/heartbeat",
     *   "cadreUrl": "http://host:8222/gbgl/heartbeat"
     * }
     */
    @PostMapping("/check")
    public ResponseEntity<Map<String, Object>> checkHeartbeats(@RequestBody Map<String, String> request) {
        Map<String, Object> result = new HashMap<>();

        String unifiedUrl = request.get("unifiedUrl");
        String cadreUrl = request.get("cadreUrl");

        try {
            Map<String, Object> unifiedResult = checkSingleHeartbeat(unifiedUrl);
            Map<String, Object> cadreResult = checkSingleHeartbeat(cadreUrl);

            result.put("success", true);
            result.put("unifiedUp", unifiedResult.get("up"));
            result.put("unifiedMessage", unifiedResult.get("message"));
            result.put("cadreUp", cadreResult.get("up"));
            result.put("cadreMessage", cadreResult.get("message"));

            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "心跳检测失败: " + e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * 单个心跳地址检测
     * 说明：仅在后端服务侧发起 HTTP 请求，不受浏览器 CORS 限制。
     */
    private Map<String, Object> checkSingleHeartbeat(String urlStr) {
        Map<String, Object> result = new HashMap<>();
        if (urlStr == null || urlStr.trim().isEmpty()) {
            result.put("up", false);
            result.put("message", "未配置心跳地址");
            return result;
        }

        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            int status = conn.getResponseCode();
            if (status >= 200 && status < 300) {
                // 读取少量响应内容，仅用于日志友好展示
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                    String line = reader.readLine();
                    result.put("up", true);
                    result.put("message", "心跳正常: " + urlStr + (line != null ? "，响应: " + line : ""));
                }
            } else {
                result.put("up", false);
                result.put("message", "心跳异常 (HTTP " + status + "): " + urlStr);
            }
        } catch (Exception e) {
            result.put("up", false);
            result.put("message", "心跳请求失败: " + urlStr + "，错误: " + e.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }

        return result;
    }
}

