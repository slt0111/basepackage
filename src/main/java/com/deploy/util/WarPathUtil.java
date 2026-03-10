package com.deploy.util;

import org.springframework.boot.system.ApplicationHome;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * WAR 包目录解析工具类
 * 说明：统一约定在应用目录（JAR 所在目录）下查找同级 wars 目录，确保无论在 IDE 运行还是打成 JAR 部署，都能稳定找到外部 WAR 包存放位置。
 */
public class WarPathUtil {

    /**
     * 私有构造方法
     * 说明：工具类不需要被实例化，防止误用。
     */
    private WarPathUtil() {
    }

    /**
     * 获取 wars 根目录路径
     * 说明：优先取 Spring Boot ApplicationHome 的目录（JAR 所在目录），然后在该目录下定位 wars 子目录；
     *      对于本地开发场景（如 IDE 启动，home 目录通常为 target/classes），会自动回溯到项目根目录并使用与项目同级的 wars 目录；
     *      若目录无法解析则退化为当前工作目录下的 wars 目录，并确保目录已创建。
     *
     * @return wars 根目录对应的 Path 对象
     */
    public static Path getWarsBaseDir() {
        ApplicationHome home = new ApplicationHome(WarPathUtil.class);
        File appDir = home.getDir();

        File baseDir;
        if (appDir != null) {
            String path = appDir.getAbsolutePath();
            // 开发场景：IDE / Maven 启动时，ApplicationHome 默认指向 target/classes，需要回溯到项目根目录
            if (path.endsWith("target" + File.separator + "classes")) {
                File targetDir = appDir.getParentFile();          // target
                File projectRoot = (targetDir != null ? targetDir.getParentFile() : null); // 项目根目录
                if (projectRoot != null) {
                    // 使用“项目同级 wars 目录”，满足本地开发时与项目并列存放 WAR 的需求
                    baseDir = new File(projectRoot, "wars");
                } else {
                    baseDir = new File("wars");
                }
            } else {
                // 部署场景：以应用目录（JAR 所在目录）作为根，在其下创建 wars 子目录，实现“与 JAR 同级的 wars 目录”需求
                baseDir = new File(appDir, "wars");
            }
        } else {
            // 兜底：home 目录无法解析时，退化为当前工作目录下的 wars 目录
            baseDir = new File("wars");
        }

        if (!baseDir.exists()) {
            // 确保目录存在，避免后续读写时报错
            baseDir.mkdirs();
        }

        return baseDir.toPath();
    }

    /**
     * 获取 configs 根目录路径（与 wars 同级）
     * 说明：逻辑与 getWarsBaseDir() 一致，仅子目录名为 configs；用于部署配置的保存与读取，与 JAR/wars 同级。
     *
     * @return configs 根目录对应的 Path 对象
     */
    public static Path getConfigsBaseDir() {
        ApplicationHome home = new ApplicationHome(WarPathUtil.class);
        File appDir = home.getDir();

        File baseDir;
        if (appDir != null) {
            String path = appDir.getAbsolutePath();
            if (path.endsWith("target" + File.separator + "classes")) {
                File targetDir = appDir.getParentFile();
                File projectRoot = (targetDir != null ? targetDir.getParentFile() : null);
                if (projectRoot != null) {
                    baseDir = new File(projectRoot, "configs");
                } else {
                    baseDir = new File("configs");
                }
            } else {
                baseDir = new File(appDir, "configs");
            }
        } else {
            baseDir = new File("configs");
        }

        if (!baseDir.exists()) {
            baseDir.mkdirs();
        }

        return baseDir.toPath();
    }

    /**
     * 获取指定应用子目录下的 WAR 存放目录路径
     * 说明：在 wars 根目录下再按应用划分子目录，例如 tyzc/、gbgl/，并在需要时自动创建。
     *
     * @param appFolder 应用子目录名称（如 "tyzc" 或 "gbgl"）
     * @return 指定应用 WAR 目录对应的 Path 对象
     */
    public static Path getAppWarsDir(String appFolder) {
        Path baseDir = getWarsBaseDir();
        Path appDir = baseDir.resolve(appFolder);
        try {
            // 确保子目录存在，方便上传 / 删除等文件操作
            Files.createDirectories(appDir);
        } catch (Exception ignored) {
            // 创建目录失败时不抛出运行时异常，后续调用方可根据文件是否存在自行处理
        }
        return appDir;
    }
}

