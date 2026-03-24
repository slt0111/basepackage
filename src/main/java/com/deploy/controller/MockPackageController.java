package com.deploy.controller;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Map;

/**
 * 模拟数据包下载控制器
 * 说明：提供“文件是否存在检查”和“文件下载”两个接口，供首页按钮调用。
 */
@RestController
@RequestMapping("/api/mock-package")
@CrossOrigin(origins = "*")
public class MockPackageController {

    /**
     * 数据包固定文件名
     * 说明：按需求约定为 resources/data/init.zip。
     */
    private static final String PACKAGE_FILE_NAME = "init.zip";

    /**
     * 检查数据包是否存在
     * 返回：{ success: true/false, exists: true/false, message: "..." }
     */
    @GetMapping("/exists")
    public ResponseEntity<Map<String, Object>> exists() {
        Path packagePath = resolvePackagePath();
        if (packagePath == null) {
            return ResponseEntity.ok(buildExistsResponse(false, "数据包不存在：resources/data/" + PACKAGE_FILE_NAME));
        }
        return ResponseEntity.ok(buildExistsResponse(true, "数据包已就绪"));
    }

    /**
     * 下载数据包
     * 行为：若文件不存在返回 404，并给出可读提示信息。
     */
    @GetMapping("/download")
    public ResponseEntity<?> download() {
        try {
            Path packagePath = resolvePackagePath();
            if (packagePath == null) {
                return ResponseEntity.status(404)
                        .body(Collections.singletonMap("message", "数据包不存在：resources/data/" + PACKAGE_FILE_NAME));
            }

            InputStreamResource resource = new InputStreamResource(new FileInputStream(packagePath.toFile()));
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + PACKAGE_FILE_NAME + "\"")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .contentLength(Files.size(packagePath))
                    .body(resource);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Collections.singletonMap("message", "下载失败: " + e.getMessage()));
        }
    }

    /**
     * 解析数据包路径
     * 说明：兼容“源码目录运行”和“打包后 classpath 运行”两种方式。
     */
    private Path resolvePackagePath() {
        // 候选 1：项目根目录下 resources/data/init.zip（与需求口径保持一致）
        Path p1 = Paths.get("resources", "data", PACKAGE_FILE_NAME);
        if (Files.exists(p1) && Files.isRegularFile(p1)) {
            return p1;
        }

        // 候选 2：源码目录下 src/main/resources/data/init.zip（本地开发常见路径）
        Path p2 = Paths.get("src", "main", "resources", "data", PACKAGE_FILE_NAME);
        if (Files.exists(p2) && Files.isRegularFile(p2)) {
            return p2;
        }

        // 候选 3：打包后 classpath:data/init.zip（jar 运行场景）
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream("data/" + PACKAGE_FILE_NAME)) {
            if (in == null) {
                return null;
            }
            // 说明：classpath 资源可能位于 jar 内，需复制到临时文件后再下载。
            Path tmp = Files.createTempFile("mock-package-", "-" + PACKAGE_FILE_NAME);
            Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            tmp.toFile().deleteOnExit();
            return tmp;
        } catch (Exception ignore) {
            return null;
        }
    }

    /**
     * 构造 exists 接口统一返回结构
     */
    private Map<String, Object> buildExistsResponse(boolean exists, String message) {
        // 说明：统一 success/exists/message 字段，便于前端直接读取。
        java.util.HashMap<String, Object> result = new java.util.HashMap<>();
        result.put("success", exists);
        result.put("exists", exists);
        result.put("message", message);
        return result;
    }
}
