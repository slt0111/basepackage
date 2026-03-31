package com.deploy.util;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.springframework.core.io.ClassPathResource;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * 文件操作工具类
 */
public class FileUtil {

    /**
     * 复制文件
     * @param source 源文件路径
     * @param target 目标文件路径
     * @throws IOException IO异常
     */
    public static void copyFile(String source, String target) throws IOException {
        File sourceFile = new File(source);
        File targetFile = new File(target);
        FileUtils.copyFile(sourceFile, targetFile);
    }

    /**
     * 从classpath复制资源文件到目标路径
     * @param resourcePath 资源路径（相对于classpath）
     * @param targetPath 目标文件路径
     * @throws IOException IO异常
     */
    public static void copyResourceToFile(String resourcePath, String targetPath) throws IOException {
        ClassPathResource resource = new ClassPathResource(resourcePath);
        File targetFile = new File(targetPath);
        // 确保目标目录存在
        targetFile.getParentFile().mkdirs();
        try (InputStream inputStream = resource.getInputStream();
             FileOutputStream outputStream = new FileOutputStream(targetFile)) {
            IOUtils.copy(inputStream, outputStream);
        }
    }

    /**
     * 创建目录
     * @param dirPath 目录路径
     * @return 创建的目录File对象
     */
    public static File createDirectory(String dirPath) {
        File dir = new File(dirPath);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    /**
     * 读取文件内容为字符串
     * @param filePath 文件路径
     * @return 文件内容
     * @throws IOException IO异常
     */
    public static String readFileContent(String filePath) throws IOException {
        return FileUtils.readFileToString(new File(filePath), StandardCharsets.UTF_8);
    }

    /**
     * 读取资源文件内容为字符串
     * @param resourcePath 资源路径
     * @return 文件内容
     * @throws IOException IO异常
     */
    public static String readResourceContent(String resourcePath) throws IOException {
        ClassPathResource resource = new ClassPathResource(resourcePath);
        try (InputStream inputStream = resource.getInputStream()) {
            return IOUtils.toString(inputStream, StandardCharsets.UTF_8);
        }
    }

    /**
     * 判断 classpath 资源是否存在
     * 说明：用于 zip/jar 等二进制资源的“存在性探测”，避免用 readResourceContent() 以 UTF-8 方式读取二进制导致误判。
     *
     * @param resourcePath 资源路径（相对于 classpath）
     * @return true 表示资源可读取，false 表示不存在或不可访问
     */
    public static boolean resourceExists(String resourcePath) {
        try {
            // 说明：在 Spring Boot 可执行 jar + 异步线程场景下，默认 ClassLoader 可能与主线程不同；
            // 这里显式使用当前线程 ContextClassLoader，避免资源存在但探测不到。
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            String p = resourcePath != null ? resourcePath.trim() : "";
            if (p.startsWith("/")) {
                p = p.substring(1);
            }

            // 先按标准路径探测
            if (canOpenClasspathResource(p, cl)) {
                return true;
            }
            // 兼容：某些环境下资源路径可能被传入为 /xxx，或需要回退到默认 ClassLoader
            if (canOpenClasspathResource(resourcePath, null)) {
                return true;
            }
            return false;
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * 尝试打开 classpath 资源输入流以确认可读性
     */
    private static boolean canOpenClasspathResource(String resourcePath, ClassLoader cl) {
        try {
            if (resourcePath == null || resourcePath.trim().isEmpty()) {
                return false;
            }
            ClassPathResource resource = (cl != null) ? new ClassPathResource(resourcePath, cl) : new ClassPathResource(resourcePath);
            if (!resource.exists()) {
                return false;
            }
            try (InputStream ignored = resource.getInputStream()) {
                return true;
            }
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * 写入文件内容
     * @param filePath 文件路径
     * @param content 文件内容
     * @throws IOException IO异常
     */
    public static void writeFileContent(String filePath, String content) throws IOException {
        File file = new File(filePath);
        // 确保父目录存在（如果父目录不为null）
        File parentFile = file.getParentFile();
        if (parentFile != null) {
            parentFile.mkdirs();
        }
        FileUtils.writeStringToFile(file, content, StandardCharsets.UTF_8);
    }

    /**
     * 替换文件中的文本内容
     * @param filePath 文件路径
     * @param oldText 旧文本
     * @param newText 新文本
     * @throws IOException IO异常
     */
    public static void replaceFileContent(String filePath, String oldText, String newText) throws IOException {
        String content = readFileContent(filePath);
        content = content.replace(oldText, newText);
        writeFileContent(filePath, content);
    }

    /**
     * 解压ZIP文件
     * @param zipPath ZIP文件路径
     * @param targetDir 目标目录
     * @throws IOException IO异常
     */
    public static void unzipFile(String zipPath, String targetDir) throws IOException {
        File targetDirFile = new File(targetDir);
        if (!targetDirFile.exists()) {
            targetDirFile.mkdirs();
        }

        try (ZipFile zipFile = new ZipFile(zipPath)) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                File entryFile = new File(targetDir, entry.getName());
                
                if (entry.isDirectory()) {
                    entryFile.mkdirs();
                } else {
                    entryFile.getParentFile().mkdirs();
                    try (InputStream inputStream = zipFile.getInputStream(entry);
                         FileOutputStream outputStream = new FileOutputStream(entryFile)) {
                        IOUtils.copy(inputStream, outputStream);
                    }
                }
            }
        }
    }

    /**
     * 从classpath解压资源ZIP文件
     * @param resourcePath 资源路径
     * @param targetDir 目标目录
     * @throws IOException IO异常
     */
    public static void unzipResource(String resourcePath, String targetDir) throws IOException {
        ClassPathResource resource = new ClassPathResource(resourcePath);
        // 先复制到临时文件
        File tempFile = File.createTempFile("temp_", ".zip");
        try {
            try (InputStream inputStream = resource.getInputStream();
                 FileOutputStream outputStream = new FileOutputStream(tempFile)) {
                IOUtils.copy(inputStream, outputStream);
            }
            // 解压临时文件
            unzipFile(tempFile.getAbsolutePath(), targetDir);
        } finally {
            // 删除临时文件
            if (tempFile.exists()) {
                tempFile.delete();
            }
        }
    }

    /**
     * 检查文件是否存在
     * @param filePath 文件路径
     * @return true表示存在，false表示不存在
     */
    public static boolean fileExists(String filePath) {
        return new File(filePath).exists();
    }

    /**
     * 删除文件或目录
     * @param path 文件或目录路径
     * @throws IOException IO异常
     */
    public static void deleteFile(String path) throws IOException {
        File file = new File(path);
        if (file.exists()) {
            if (file.isDirectory()) {
                FileUtils.deleteDirectory(file);
            } else {
                FileUtils.deleteQuietly(file);
            }
        }
    }
}

