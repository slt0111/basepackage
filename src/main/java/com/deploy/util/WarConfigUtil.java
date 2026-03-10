package com.deploy.util;

import com.deploy.websocket.DeployLogWebSocket;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * WAR 包内部配置文件替换工具类
 * 说明：用于在部署前将 WAR 包中的 YML 配置文件替换为当前界面生成的配置内容，Tomcat 与 TongWeb 共用。
 */
public class WarConfigUtil {

    /**
     * 私有构造方法
     * 说明：工具类不需要被实例化，防止误用。
     */
    private WarConfigUtil() {
    }

    /**
     * 直接替换WAR包中的配置文件（不解压重新打包）
     * 使用 ZipFile 读取原 WAR，再写入新的临时 WAR，最后用临时文件覆盖原文件。
     *
     * @param warPath     WAR包物理路径
     * @param ymlContent  YML配置内容（最终写入 application-dev-dm.yml）
     * @param warFileName WAR包文件名（仅用于日志）
     * @throws IOException IO异常
     */
    public static void replaceWarConfigFileDirect(String warPath, String ymlContent, String warFileName) throws IOException {
        DeployLogWebSocket.sendLog("正在替换WAR包中的配置文件: " + warFileName);

        // 配置文件路径：WEB-INF/classes/application-dev-dm.yml
        String configEntryName = "WEB-INF/classes/application-dev-dm.yml";

        // 创建临时WAR文件
        File warFile = new File(warPath);
        File tempWarFile = new File(warPath + ".tmp");

        try (java.util.zip.ZipFile zipFile = new java.util.zip.ZipFile(warFile);
             ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(tempWarFile))) {

            // 遍历原WAR包中的所有条目
            java.util.Enumeration<? extends ZipEntry> entries = zipFile.entries();
            boolean configReplaced = false;

            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();

                // 如果是目标配置文件，替换为新内容
                if (entry.getName().equals(configEntryName)) {
                    DeployLogWebSocket.sendLog("找到配置文件，正在替换: " + configEntryName);

                    // 创建新的ZIP条目
                    ZipEntry newEntry = new ZipEntry(configEntryName);
                    zos.putNextEntry(newEntry);

                    // 写入新的配置内容
                    byte[] contentBytes = ymlContent.getBytes("UTF-8");
                    zos.write(contentBytes);
                    zos.closeEntry();

                    configReplaced = true;
                } else {
                    // 复制其他条目
                    ZipEntry newEntry = new ZipEntry(entry.getName());
                    newEntry.setMethod(entry.getMethod());
                    if (entry.getTime() != -1) {
                        newEntry.setTime(entry.getTime());
                    }
                    if (entry.getSize() != -1) {
                        newEntry.setSize(entry.getSize());
                    }
                    if (entry.getCrc() != -1) {
                        newEntry.setCrc(entry.getCrc());
                    }

                    zos.putNextEntry(newEntry);

                    // 复制文件内容
                    try (java.io.InputStream is = zipFile.getInputStream(entry)) {
                        byte[] buffer = new byte[8192];
                        int length;
                        while ((length = is.read(buffer)) > 0) {
                            zos.write(buffer, 0, length);
                        }
                    }

                    zos.closeEntry();
                }
            }

            // 如果配置文件不存在，需要添加
            if (!configReplaced) {
                DeployLogWebSocket.sendLog("配置文件不存在，正在添加: " + configEntryName);
                ZipEntry newEntry = new ZipEntry(configEntryName);
                zos.putNextEntry(newEntry);
                byte[] contentBytes = ymlContent.getBytes("UTF-8");
                zos.write(contentBytes);
                zos.closeEntry();
            }

        }

        // 替换原文件
        if (warFile.delete() && tempWarFile.renameTo(warFile)) {
            DeployLogWebSocket.sendLog("配置文件替换完成: " + configEntryName);
        } else {
            throw new IOException("替换WAR包文件失败");
        }
    }
}

