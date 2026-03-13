package com.deploy.util;

import org.springframework.boot.system.ApplicationHome;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 模拟数据导出路径工具类
 * 说明：与 {@link WarPathUtil} 的设计一致，确保 IDE 运行与 JAR 部署两种场景都能稳定写入导出产物。
 */
public class ExportPathUtil {

    /**
     * 私有构造
     * 说明：工具类不需要实例化。
     */
    private ExportPathUtil() {
    }

    /**
     * 获取导出产物根目录（与 wars/configs 同级）
     * 说明：默认使用应用目录（JAR 所在目录）下的 generated/mock-export；
     *      IDE 场景下会回溯到项目根目录并使用项目下 generated/mock-export。
     */
    public static Path getMockExportBaseDir() {
        ApplicationHome home = new ApplicationHome(ExportPathUtil.class);
        File appDir = home.getDir();

        File baseDir;
        if (appDir != null) {
            String path = appDir.getAbsolutePath();
            if (path.endsWith("target" + File.separator + "classes")) {
                File targetDir = appDir.getParentFile(); // target
                File projectRoot = (targetDir != null ? targetDir.getParentFile() : null);
                if (projectRoot != null) {
                    baseDir = new File(projectRoot, "generated" + File.separator + "mock-export");
                } else {
                    baseDir = new File("generated" + File.separator + "mock-export");
                }
            } else {
                baseDir = new File(appDir, "generated" + File.separator + "mock-export");
            }
        } else {
            baseDir = new File("generated" + File.separator + "mock-export");
        }

        try {
            Files.createDirectories(baseDir.toPath());
        } catch (Exception ignored) {
            // 说明：目录创建失败时不抛出运行时异常，后续调用方可根据文件写入异常进行提示。
        }

        return baseDir.toPath();
    }

    /**
     * 获取模拟数据导入根目录（与 mock-export 同级，用于上传 zip 与解压任务目录）
     * 说明：目录结构为 generated/mock-import；uploads 与 jobId 子目录由 MockImportService 创建。
     */
    public static Path getMockImportBaseDir() {
        ApplicationHome home = new ApplicationHome(ExportPathUtil.class);
        File appDir = home.getDir();
        File baseDir;
        if (appDir != null) {
            String path = appDir.getAbsolutePath();
            if (path.endsWith("target" + File.separator + "classes")) {
                File targetDir = appDir.getParentFile();
                File projectRoot = (targetDir != null ? targetDir.getParentFile() : null);
                if (projectRoot != null) {
                    baseDir = new File(projectRoot, "generated" + File.separator + "mock-import");
                } else {
                    baseDir = new File("generated" + File.separator + "mock-import");
                }
            } else {
                baseDir = new File(appDir, "generated" + File.separator + "mock-import");
            }
        } else {
            baseDir = new File("generated" + File.separator + "mock-import");
        }
        try {
            Files.createDirectories(baseDir.toPath());
        } catch (Exception ignored) {
        }
        return baseDir.toPath();
    }
}

