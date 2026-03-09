package com.deploy.controller;

import com.deploy.util.FileUtil;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * YML配置管理控制器
 */
@RestController
@RequestMapping("/api/yml")
@CrossOrigin(origins = "*")
public class YmlController {

    private static final String TEMPLATE_DIR = "yml";
    private static final String GENERATED_DIR = "generated/yml";

    /**
     * 获取YML模板
     */
    @GetMapping("/template/{app}")
    public ResponseEntity<Map<String, Object>> getTemplate(@PathVariable String app) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            // 确定模板文件路径
            String templatePath;
            if ("unified".equals(app)) {
                templatePath = TEMPLATE_DIR + "/tyzc/application-dev-dm.yml";
            } else {
                templatePath = TEMPLATE_DIR + "/gbgl/application-dev-dm.yml";
            }
            
            // 读取模板文件
            String content = FileUtil.readResourceContent(templatePath);
            
            result.put("success", true);
            result.put("content", content);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            // 如果模板不存在，返回默认内容
            result.put("success", true);
            result.put("content", "# YML配置文件\n# 请在此编辑配置内容\n");
            return ResponseEntity.ok(result);
        }
    }

    /**
     * 保存YML配置
     */
    @PostMapping("/save")
    public ResponseEntity<Map<String, Object>> saveYml(@RequestBody Map<String, Object> request) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            String app = (String) request.get("app");
            String content = (String) request.get("content");
            
            if (app == null || content == null) {
                result.put("success", false);
                result.put("message", "参数不完整");
                return ResponseEntity.badRequest().body(result);
            }
            
            // 确定保存路径
            String fileName;
            if ("unified".equals(app)) {
                fileName = "tyzc-application.yml";
            } else {
                fileName = "gbgl-application.yml";
            }
            
            // 确保生成目录存在
            FileUtil.createDirectory(GENERATED_DIR);
            String filePath = GENERATED_DIR + "/" + fileName;
            FileUtil.writeFileContent(filePath, content);
            
            result.put("success", true);
            result.put("message", "YML配置保存成功");
            result.put("filePath", filePath);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "保存失败: " + e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }
}

