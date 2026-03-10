package com.deploy.controller;

import com.deploy.util.FileUtil;
import com.deploy.util.WarPathUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * WAR包管理控制器
 * 说明：统一从项目同级 wars 目录读取 / 管理 WAR 包，并在首次运行时自动从 classpath 迁移内置 WAR 到外部目录。
 */
@RestController
@RequestMapping("/api/war")
@CrossOrigin(origins = "*")
public class WarController {

    /**
     * 日志记录器
     * 说明：用于在后端控制台输出 WAR 上传、删除等操作的详细信息，便于运维排查问题。
     */
    private static final Logger logger = LoggerFactory.getLogger(WarController.class);

    /**
     * 类路径下 WAR 资源目录（用于首启迁移内置 WAR 到外部 wars 目录）
     */
    private static final String RESOURCE_WAR_TYZC_DIR = "wars/tyzc/";
    private static final String RESOURCE_WAR_GBGL_DIR = "wars/gbgl/";

    /**
     * 获取可用 WAR 包列表
     * 说明：优先从外部 wars 目录（项目同级 wars 文件夹）读取；若为空则尝试从 classpath 迁移内置 WAR 到外部目录后再读取。
     */
    @GetMapping("/list")
    public ResponseEntity<Map<String, Object>> listBuiltinWars() {
        Map<String, Object> result = new HashMap<>();

        try {
            Map<String, Object> wars = new HashMap<>();

            // 统一支撑 WAR：优先从外部 wars/tyzc 目录读取
            List<String> unifiedWars = listWarFilesFromDir(WarPathUtil.getAppWarsDir("tyzc"));
            if (unifiedWars.isEmpty()) {
                // 首次运行或目录为空时，尝试从 classpath 迁移内置 WAR 到外部目录
                unifiedWars = migrateClasspathWarsToExternalDir(RESOURCE_WAR_TYZC_DIR, WarPathUtil.getAppWarsDir("tyzc"));
            }

            // 干部应用 WAR：优先从外部 wars/gbgl 目录读取
            List<String> cadreWars = listWarFilesFromDir(WarPathUtil.getAppWarsDir("gbgl"));
            if (cadreWars.isEmpty()) {
                // 首次运行或目录为空时，尝试从 classpath 迁移内置 WAR 到外部目录
                cadreWars = migrateClasspathWarsToExternalDir(RESOURCE_WAR_GBGL_DIR, WarPathUtil.getAppWarsDir("gbgl"));
            }

            // 返回WAR包列表（如果有多个，返回第一个作为默认值，同时返回完整列表，保持与前端现有接口兼容）
            if (!unifiedWars.isEmpty()) {
                wars.put("unified", unifiedWars.get(0));      // 默认使用第一个
                wars.put("unifiedList", unifiedWars);         // 返回完整列表
            }
            if (!cadreWars.isEmpty()) {
                wars.put("cadre", cadreWars.get(0));          // 默认使用第一个
                wars.put("cadreList", cadreWars);             // 返回完整列表
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
     * 说明：根据 app（unified/cadre）上传到对应的 wars 子目录（tyzc/ 或 gbgl/），同名文件会先删除再覆盖，实现“删除后重新上传”的语义。
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

            String originalFileName = file.getOriginalFilename();
            if (originalFileName == null || !originalFileName.toLowerCase().endsWith(".war")) {
                result.put("success", false);
                result.put("message", "只支持WAR文件");
                return ResponseEntity.badRequest().body(result);
            }

            String appFolder = resolveAppFolder(app);
            if (appFolder == null) {
                result.put("success", false);
                result.put("message", "不支持的应用标识: " + app);
                return ResponseEntity.badRequest().body(result);
            }

            // 归一化文件名：仅使用文件名部分，避免携带路径信息
            String safeFileName = new File(originalFileName).getName();
            Path appDir = WarPathUtil.getAppWarsDir(appFolder);
            Path targetPath = appDir.resolve(safeFileName);

            // 如存在同名文件，先删除后写入，保证“重新上传”不残留旧文件
            if (Files.exists(targetPath)) {
                Files.delete(targetPath);
            }
            Files.copy(file.getInputStream(), targetPath);

            // 上传成功后输出日志，包含应用标识与物理存储路径，便于在后端快速定位文件
            logger.info("WAR 上传成功, app={}, fileName={}, path={}", app, safeFileName, targetPath.toString());

            result.put("success", true);
            result.put("message", "上传成功");
            result.put("fileName", safeFileName);
            result.put("filePath", targetPath.toString());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "上传失败: " + e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * 删除指定应用下的 WAR 包
     * 说明：前端在 WAR 列表中点击删除后，调用该接口删除外部 wars 目录中的对应文件。
     */
    @DeleteMapping("/delete")
    public ResponseEntity<Map<String, Object>> deleteWar(
            @RequestParam("app") String app,
            @RequestParam("fileName") String fileName) {
        Map<String, Object> result = new HashMap<>();
        try {
            String appFolder = resolveAppFolder(app);
            if (appFolder == null) {
                result.put("success", false);
                result.put("message", "不支持的应用标识: " + app);
                return ResponseEntity.badRequest().body(result);
            }
            if (fileName == null || fileName.trim().isEmpty()) {
                result.put("success", false);
                result.put("message", "文件名不能为空");
                return ResponseEntity.badRequest().body(result);
            }

            Path appDir = WarPathUtil.getAppWarsDir(appFolder);
            Path targetPath = appDir.resolve(fileName);
            if (!Files.exists(targetPath)) {
                result.put("success", false);
                result.put("message", "文件不存在或已被删除");
                return ResponseEntity.ok(result);
            }

            Files.delete(targetPath);
            result.put("success", true);
            result.put("message", "删除成功");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "删除失败: " + e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * 从指定目录读取所有 .war 文件名
     * 说明：仅返回文件名字符串列表，不包含完整路径，便于前端显示和后续部署逻辑使用。
     *
     * @param dir WAR 目录路径
     * @return 该目录下所有 .war 文件名列表
     * @throws java.io.IOException IO 异常
     */
    private List<String> listWarFilesFromDir(Path dir) throws java.io.IOException {
        List<String> warFiles = new ArrayList<>();
        if (dir == null || !Files.exists(dir)) {
            return warFiles;
        }
        // Java 8 写法：遍历目录中文件并筛选出所有 .war 文件
        try (java.util.stream.Stream<Path> stream = Files.list(dir)) {
            stream.filter(path -> Files.isRegularFile(path)
                            && path.getFileName().toString().toLowerCase().endsWith(".war"))
                    .forEach(path -> warFiles.add(path.getFileName().toString()));
        }
        return warFiles;
    }

    /**
     * 从 classpath 迁移内置 WAR 文件到外部 wars 目录
     * 说明：用于老版本升级场景，在外部目录为空时，将打包在 JAR 内的默认 WAR 自动复制到外部目录，便于后续运维直接替换文件。
     *
     * @param classpathDir classpath 中的 WAR 目录前缀（例如 wars/tyzc/）
     * @param targetDir    外部目标目录路径
     * @return 迁移后得到的 .war 文件名列表
     */
    private List<String> migrateClasspathWarsToExternalDir(String classpathDir, Path targetDir) {
        List<String> migrated = new ArrayList<>();
        try {
            ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath:" + classpathDir + "*.war");
            if (resources != null && resources.length > 0) {
                for (Resource resource : resources) {
                    String fileName = resource.getFilename();
                    if (fileName == null) {
                        continue;
                    }
                    Path targetPath = targetDir.resolve(fileName);
                    // 仅当目标文件不存在时才执行迁移，避免覆盖运维已上传的 WAR 文件
                    if (!Files.exists(targetPath)) {
                        FileUtil.copyResourceToFile(classpathDir + fileName, targetPath.toString());
                    }
                    migrated.add(fileName);
                }
            }
        } catch (Exception e) {
            // 迁移失败不影响整体功能，仅打印堆栈便于排查
            e.printStackTrace();
        }
        return migrated;
    }

    /**
     * 将前端传入的应用标识转换为实际目录名
     * 说明：前端统一使用 unified/cadre 作为应用标识，这里映射到物理目录 tyzc/gbgl，减少前后端强耦合。
     *
     * @param app 前端传入的应用标识（unified/cadre）
     * @return 对应的目录名称（tyzc/gbgl），不匹配时返回 null
     */
    private String resolveAppFolder(String app) {
        if (app == null) {
            return null;
        }
        String lower = app.toLowerCase();
        if ("unified".equals(lower)) {
            return "tyzc";
        }
        if ("cadre".equals(lower)) {
            return "gbgl";
        }
        return null;
    }
}


