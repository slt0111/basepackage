package com.deploy.controller;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * WAR包管理控制器
 */
@RestController
@RequestMapping("/api/war")
@CrossOrigin(origins = "*")
public class WarController {

    private static final String UPLOAD_DIR = "uploads/wars";
    private static final String RESOURCE_WAR_TYZC_DIR = "wars/tyzc/";
    private static final String RESOURCE_WAR_GBGL_DIR = "wars/gbgl/";

    /**
     * 获取内置WAR包列表（动态扫描资源目录）
     */
    @GetMapping("/list")
    public ResponseEntity<Map<String, Object>> listBuiltinWars() {
        Map<String, Object> result = new HashMap<>();
        
        try {
            Map<String, Object> wars = new HashMap<>();
            ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            
            // 扫描统一支撑WAR包目录，返回所有WAR包
            java.util.List<String> unifiedWars = new java.util.ArrayList<>();
            try {
                Resource[] tyzcResources = resolver.getResources("classpath:" + RESOURCE_WAR_TYZC_DIR + "*.war");
                if (tyzcResources != null && tyzcResources.length > 0) {
                    for (Resource resource : tyzcResources) {
                        String fileName = resource.getFilename();
                        if (fileName != null) {
                            unifiedWars.add(fileName);
                        }
                    }
                }
            } catch (Exception e) {
                // 如果扫描失败，尝试使用默认路径
                ClassPathResource tyzcResource = new ClassPathResource(RESOURCE_WAR_TYZC_DIR + "tyzc.war");
                if (tyzcResource.exists()) {
                    unifiedWars.add("tyzc.war");
                }
            }
            
            // 扫描干部应用WAR包目录，返回所有WAR包
            java.util.List<String> cadreWars = new java.util.ArrayList<>();
            try {
                Resource[] gbglResources = resolver.getResources("classpath:" + RESOURCE_WAR_GBGL_DIR + "*.war");
                if (gbglResources != null && gbglResources.length > 0) {
                    for (Resource resource : gbglResources) {
                        String fileName = resource.getFilename();
                        if (fileName != null) {
                            cadreWars.add(fileName);
                        }
                    }
                }
            } catch (Exception e) {
                // 如果扫描失败，尝试使用默认路径
                ClassPathResource gbglResource = new ClassPathResource(RESOURCE_WAR_GBGL_DIR + "gbgl.war");
                if (gbglResource.exists()) {
                    cadreWars.add("gbgl.war");
                }
            }
            
            // 返回WAR包列表（如果有多个，返回第一个作为默认值，同时返回完整列表）
            if (!unifiedWars.isEmpty()) {
                wars.put("unified", unifiedWars.get(0)); // 默认使用第一个
                wars.put("unifiedList", unifiedWars); // 返回完整列表
            }
            if (!cadreWars.isEmpty()) {
                wars.put("cadre", cadreWars.get(0)); // 默认使用第一个
                wars.put("cadreList", cadreWars); // 返回完整列表
            }
            
            result.put("success", true);
            result.put("wars", wars);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "获取WAR包列表失败: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * 上传WAR包
     */
    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> uploadWar(
            @RequestParam("file") MultipartFile file,
            @RequestParam("app") String app) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            if (file.isEmpty()) {
                result.put("success", false);
                result.put("message", "文件为空");
                return ResponseEntity.badRequest().body(result);
            }
            
            if (!file.getOriginalFilename().endsWith(".war")) {
                result.put("success", false);
                result.put("message", "只支持WAR文件");
                return ResponseEntity.badRequest().body(result);
            }
            
            // 创建上传目录
            File uploadDir = new File(UPLOAD_DIR);
            if (!uploadDir.exists()) {
                uploadDir.mkdirs();
            }
            
            // 保存文件
            String fileName = app + "_" + System.currentTimeMillis() + ".war";
            Path filePath = Paths.get(UPLOAD_DIR, fileName);
            Files.write(filePath, file.getBytes());
            
            result.put("success", true);
            result.put("message", "上传成功");
            result.put("fileName", fileName);
            result.put("filePath", filePath.toString());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "上传失败: " + e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }
}

