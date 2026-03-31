package com.deploy.service;

import com.deploy.model.DatabaseConfig;
import com.deploy.model.DeployConfig;
import com.deploy.websocket.DeployLogWebSocket;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.stream.Collectors;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

/**
 * 数据初始化服务
 * 说明：读取 resources/data/init 下的 SQL 模板，按参数替换后生成可预览/下载/执行的脚本清单。
 */
@Service
public class DataInitService {

    /**
     * 数据初始化模板目录候选路径
     * 说明：优先读取外置 resources/data/init；其次读取源码目录 src/main/resources/data/init。
     */
    private static final String[] TEMPLATE_DIR_CANDIDATES = new String[]{
            "resources/data/sql",
            "src/main/resources/data/sql"
    };

    /**
     * 兜底模板目录（兼容历史 SQL 目录）
     */
    private static final String FALLBACK_TEMPLATE_DIR = "src/main/resources/data/init";

    /**
     * 预览数据初始化脚本（参数替换后）
     */
    public Map<String, Object> preview(DeployConfig deployConfig, String app, Map<String, String> initParams) throws Exception {
        List<Path> templateFiles = listTemplateSqlFiles(app);
        Map<String, String> variables = buildVariables(deployConfig, initParams);

        List<Map<String, Object>> scripts = new ArrayList<>();
        StringBuilder merged = new StringBuilder();

        for (Path file : templateFiles) {
            // 兼容 Java 8：使用 readAllBytes + UTF-8 解码替代 Files.readString
            String raw = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            String fileName = file.getFileName().toString();
            String rendered = renderTemplateByFile(fileName, raw, deployConfig, app, variables);

            Map<String, Object> item = new HashMap<>();
            item.put("fileName", fileName);
            item.put("content", rendered);
            item.put("lineCount", countLines(rendered));
            scripts.add(item);

            merged.append("-- =====================================\n");
            merged.append("-- FILE: ").append(fileName).append("\n");
            merged.append("-- =====================================\n");
            merged.append(rendered).append("\n\n");
        }

        Map<String, Object> result = new HashMap<>();
        result.put("app", app);
        result.put("scripts", scripts);
        result.put("mergedScript", merged.toString());
        result.put("scriptCount", scripts.size());
        return result;
    }

    /**
     * 在线执行初始化脚本
     * 说明：按预览同样逻辑先生成最终 SQL，再逐条执行。
     */
    public Map<String, Object> execute(DeployConfig deployConfig, String app, Map<String, String> initParams) throws Exception {
        DatabaseConfig db = resolveDbConfig(deployConfig, app);
        if (db == null) {
            throw new IllegalArgumentException("未找到数据库配置，请先完成参数配置");
        }
        if (isBlank(db.getConnectionString()) || isBlank(db.getUsername())) {
            throw new IllegalArgumentException("数据库连接信息不完整，请检查连接串、用户名配置");
        }

        List<Path> templateFiles = listTemplateSqlFiles(app);
        Map<String, String> variables = buildVariables(deployConfig, initParams);

        int successCount = 0;
        int failCount = 0;
        int totalStatements = 0;

        DeployLogWebSocket.sendLog("========== 开始执行数据初始化 ==========");
        DeployLogWebSocket.sendLog("目标应用: " + ("cadre".equalsIgnoreCase(app) ? "干部应用" : "统一支撑"));
        DeployLogWebSocket.sendLog("模板文件数量: " + templateFiles.size());

        try (Connection conn = DriverManager.getConnection(db.getConnectionString(), db.getUsername(), db.getPassword())) {
            conn.setAutoCommit(false);
            for (Path file : templateFiles) {
                String fileName = file.getFileName().toString();
                // 兼容 Java 8：使用 readAllBytes + UTF-8 解码替代 Files.readString
                String raw = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                String rendered = renderTemplateByFile(fileName, raw, deployConfig, app, variables);
                List<String> statements = splitSqlStatements(rendered);

                DeployLogWebSocket.sendLog("执行脚本文件: " + fileName + "，SQL条数: " + statements.size());
                try (Statement st = conn.createStatement()) {
                    for (String sql : statements) {
                        totalStatements++;
                        try {
                            st.execute(sql);
                            successCount++;
                        } catch (Exception e) {
                            failCount++;
                            DeployLogWebSocket.sendLog("SQL执行失败: " + e.getMessage());
                            DeployLogWebSocket.sendLog("失败SQL片段: " + abbreviate(sql, 180));
                        }
                    }
                }
            }
            conn.commit();
        } catch (Exception e) {
            DeployLogWebSocket.sendLog("数据初始化执行异常: " + e.getMessage());
            throw e;
        }

        DeployLogWebSocket.sendLog("========== 数据初始化执行完成 ==========");
        DeployLogWebSocket.sendLog("SQL总数: " + totalStatements + "，成功: " + successCount + "，失败: " + failCount);

        Map<String, Object> result = new HashMap<>();
        result.put("totalStatements", totalStatements);
        result.put("successCount", successCount);
        result.put("failCount", failCount);
        return result;
    }

    /**
     * 列出模板 SQL 文件（按文件名排序）
     */
    private List<Path> listTemplateSqlFiles(String app) throws IOException {
        Path dir = resolveTemplateDir(app);
        if (dir != null && Files.isDirectory(dir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.sql")) {
                List<Path> files = new ArrayList<>();
                for (Path p : stream) {
                    if (Files.isRegularFile(p)) {
                        files.add(p);
                    }
                }
                if (files.isEmpty()) {
                    throw new IllegalStateException("初始化 SQL 目录下未找到 .sql 文件: " + dir.toAbsolutePath());
                }
                return files.stream()
                        .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                        .collect(Collectors.toList());
            }
        }

        // 说明：jar 启动时 SQL 模板在 classpath（jar 内部），无法用 Files.isDirectory(Paths.get(...)) 判断。
        // 这里兜底从 classpath 读取并解压到临时目录，保证后续仍可按 Path 列表读取内容。
        List<Path> classpathFiles = extractClasspathSqlFilesToTemp(app);
        if (classpathFiles.isEmpty()) {
            throw new IllegalStateException("未找到初始化 SQL 目录：请检查 data/sql/(tyzc|gbgl) 或旧目录 data/init");
        }
        return classpathFiles;
    }

    /**
     * 解析模板目录
     */
    private Path resolveTemplateDir(String app) {
        String appDir = resolveAppDirName(app);
        // 新规则：优先读取按应用分目录（.../init/tyzc 或 .../init/gbgl）
        for (String candidate : TEMPLATE_DIR_CANDIDATES) {
            Path p = Paths.get(candidate, appDir);
            if (Files.exists(p) && Files.isDirectory(p)) {
                return p;
            }
        }
        // 兼容：若新目录不存在，回退到旧的 init 根目录
        for (String candidate : TEMPLATE_DIR_CANDIDATES) {
            Path p = Paths.get(candidate);
            if (Files.exists(p) && Files.isDirectory(p)) {
                return p;
            }
        }
        // 历史兜底目录（兼容旧的 data/init 结构）
        Path fallback = Paths.get(FALLBACK_TEMPLATE_DIR);
        if (Files.exists(fallback) && Files.isDirectory(fallback)) {
            return fallback;
        }
        return null;
    }

    /**
     * 从 classpath（含 jar 内部资源）提取 SQL 模板到临时目录，并返回可读取的 Path 列表。
     * 说明：避免直接对 jar 内 Path 进行 DirectoryStream 枚举导致的兼容性问题。
     */
    private List<Path> extractClasspathSqlFilesToTemp(String app) throws IOException {
        String appDir = resolveAppDirName(app);
        // 说明：优先新目录 data/sql/{appDir}；其次尝试 data/sql；最后兼容旧目录 data/init。
        List<String> candidateDirs = Arrays.asList(
                "data/sql/" + appDir,
                "data/sql",
                "data/init"
        );

        // 说明：Spring Boot 可执行 jar（BOOT-INF/classes）属于“嵌套 jar”加载方式，不能依赖 NIO jar 文件系统枚举目录。
        // 这里改为用 Spring 的 classpath* 资源扫描（ResourcePatternResolver）找到 *.sql，再拷贝到临时目录供后续按 Path 读取。
        ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver(Thread.currentThread().getContextClassLoader());
        for (String dir : candidateDirs) {
            try {
                Resource[] resources = resolver.getResources("classpath*:" + dir + "/*.sql");
                if (resources == null || resources.length == 0) {
                    continue;
                }

                Path tempRoot = Files.createTempDirectory("data-init-sql-");
                tempRoot.toFile().deleteOnExit();
                List<Path> extracted = new ArrayList<>();
                for (Resource r : resources) {
                    if (r == null || !r.exists()) {
                        continue;
                    }
                    String name = r.getFilename();
                    if (name == null || name.trim().isEmpty() || !name.toLowerCase(Locale.ROOT).endsWith(".sql")) {
                        continue;
                    }
                    Path dst = tempRoot.resolve(name);
                    Files.copy(r.getInputStream(), dst, StandardCopyOption.REPLACE_EXISTING);
                    dst.toFile().deleteOnExit();
                    extracted.add(dst);
                }
                if (!extracted.isEmpty()) {
                    return extracted.stream()
                            .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                            .collect(Collectors.toList());
                }
            } catch (Exception ignored) {
                // 说明：逐个候选目录尝试；单个目录扫描失败不影响后续兜底路径。
            }
        }
        return Collections.emptyList();
    }

    /**
     * 构造替换变量表
     * 说明：优先使用本次请求参数；其次使用已保存到 DeployConfig 的参数。
     */
    private Map<String, String> buildVariables(DeployConfig deployConfig, Map<String, String> initParams) {
        Map<String, String> vars = new HashMap<>();

        String hzbPath = firstNotBlank(valueOf(initParams, "HZB_PATH"), deployConfig != null ? deployConfig.getHzbPath() : null);
        String hzbPathLinux = firstNotBlank(valueOf(initParams, "HZB_PATH_LINUX"), deployConfig != null ? deployConfig.getHzbPathLinux() : null);
        String uploadFilesDir = firstNotBlank(valueOf(initParams, "UPLOAD_FILES_DIR"), deployConfig != null ? deployConfig.getUploadFilesDir() : null);

        vars.put("HZB_PATH", nvl(hzbPath));
        vars.put("HZB_PATH_LINUX", nvl(hzbPathLinux));
        vars.put("UPLOAD_FILES_DIR", nvl(uploadFilesDir));
        // RPCSERVERURL 规则：服务器 URL + /tyzc-api
        vars.put("RPCSERVERURL", buildUrlPath(deployConfig != null ? deployConfig.getServerUrl() : null, "/tyzc-api"));
        // 业务机构默认参数：BUSTYPE 固定 ZZGBGL，BUSSCHEMANAME 在渲染 org.sql 时按应用覆盖
        vars.put("BUSTYPE", "ZZGBGL");
        return vars;
    }

    /**
     * 按文件名执行定制渲染
     * 说明：app.sql / org.sql 含业务规则，其他文件走通用占位符替换。
     */
    private String renderTemplateByFile(String fileName, String raw, DeployConfig deployConfig, String app, Map<String, String> vars) {
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if ("app.sql".equals(lower)) {
            return renderAppSql(raw, deployConfig, vars);
        }
        if ("org.sql".equals(lower)) {
            return renderOrgSql(raw, deployConfig, app, vars);
        }
        return replaceVariables(raw, vars);
    }

    /**
     * app.sql 渲染规则
     * - 根据参数配置中的应用，生成多条 PMT_APP 插入语句
     * - 统一支撑：appid/appcode=UCENTER
     * - 干部应用：appid/appcode=ZZGBGL
     * - APPNAME/APPSHORTNAME=应用名称
     * - APPSSOURL、APPAPIURL按服务器 URL 拼接规则生成
     */
    private String renderAppSql(String raw, DeployConfig deployConfig, Map<String, String> vars) {
        List<AppInitItem> apps = resolveConfiguredApps(deployConfig);
        StringBuilder inserts = new StringBuilder();
        for (AppInitItem item : apps) {
            DatabaseConfig db = "cadre".equals(item.key) ? resolveDbConfig(deployConfig, "cadre") : resolveDbConfig(deployConfig, "unified");
            String dbType = "DM";
            String dbUrl = db != null ? nvl(db.getConnectionString()) : "";
            String dbUsername = db != null ? nvl(db.getUsername()) : "";
            String dbPassword = db != null ? nvl(db.getPassword()) : "";
            String userChangeInterface = "";
            String active = "1";
            String ssoUrl = buildUrlPath(deployConfig != null ? deployConfig.getServerUrl() : null, item.ssoPath);
            String apiUrl = buildUrlPath(deployConfig != null ? deployConfig.getServerUrl() : null, item.apiPath);

            inserts.append("INSERT INTO PMT_APP (APPID, APPCODE, APPNAME, APPSHORTNAME, APPSSOURL, APPAPIURL, USERCHANGEINTERFACE, DBTYPE, DBURL, DBUSERNAME, DBUSERPWD, ACTIVE) VALUES(")
                    .append(sqlStr(item.appId)).append(", ")
                    .append(sqlStr(item.appCode)).append(", ")
                    .append(sqlStr(item.appName)).append(", ")
                    .append(sqlStr(item.appName)).append(", ")
                    .append(sqlStr(ssoUrl)).append(", ")
                    .append(sqlStr(apiUrl)).append(", ")
                    .append(sqlStr(userChangeInterface)).append(", ")
                    .append(sqlStr(dbType)).append(", ")
                    .append(sqlStr(dbUrl)).append(", ")
                    .append(sqlStr(dbUsername)).append(", ")
                    .append(sqlStr(dbPassword)).append(", ")
                    .append(sqlStr(active))
                    .append(");\n");
        }

        String rendered = raw.replaceAll("(?i)INSERT\\s+INTO\\s+PMT_APP\\s*\\([^\\n]*\\)\\s*values\\s*\\([^\\n]*\\)", java.util.regex.Matcher.quoteReplacement(inserts.toString().trim()));
        return replaceVariables(rendered, vars);
    }

    /**
     * org.sql 渲染规则
     * - BUSTYPE 固定为 ZZGBGL
     * - BUSSCHEMANAME 对应 ZZGBGL 业务域，固定优先使用“干部应用”数据库用户名（模式名）
     */
    private String renderOrgSql(String raw, DeployConfig deployConfig, String app, Map<String, String> vars) {
        Map<String, String> local = new HashMap<>(vars);
        local.put("BUSTYPE", "ZZGBGL");
        // 规则修正：ZZGBGL 对应干部应用模式名，不随当前 app 切换
        DatabaseConfig cadreDb = resolveDbConfig(deployConfig, "cadre");
        String schema = cadreDb != null ? nvl(cadreDb.getUsername()) : "";
        // 兜底：若干部应用未配置，则回退当前 app 对应模式，避免出现空模式名
        if (schema.trim().isEmpty()) {
            DatabaseConfig currentDb = resolveDbConfig(deployConfig, app);
            schema = currentDb != null ? nvl(currentDb.getUsername()) : "";
        }
        local.put("BUSSCHEMANAME", schema);
        return replaceVariables(raw, local);
    }

    /**
     * 从参数配置推导应用清单
     * 说明：优先读取 warFiles 前缀（tyzc/gbgl），若缺失则默认两套应用都生成。
     */
    private List<AppInitItem> resolveConfiguredApps(DeployConfig deployConfig) {
        boolean hasUnified = false;
        boolean hasCadre = false;
        if (deployConfig != null && deployConfig.getWarFiles() != null) {
            for (String war : deployConfig.getWarFiles()) {
                if (war == null) {
                    continue;
                }
                String w = war.toLowerCase(Locale.ROOT);
                if (w.startsWith("tyzc/")) {
                    hasUnified = true;
                }
                if (w.startsWith("gbgl/")) {
                    hasCadre = true;
                }
            }
        }
        // 未提供应用清单时默认生成两套，保证初始化脚本可用性
        if (!hasUnified && !hasCadre) {
            hasUnified = true;
            hasCadre = true;
        }
        List<AppInitItem> list = new ArrayList<>();
        if (hasUnified) {
            list.add(new AppInitItem("unified", "UCENTER", "UCENTER", "统一支撑应用", "/tyzc/", "/tyzc-api"));
        }
        if (hasCadre) {
            list.add(new AppInitItem("cadre", "ZZGBGL", "ZZGBGL", "干部应用", "/gbgl-front/", "/gbgl"));
        }
        return list;
    }

    /**
     * 执行模板变量替换
     * 支持格式：
     * - ${HZB_PATH}
     * 说明：仅替换 ${KEY}，避免误替换普通 SQL 文本中的同名标识。
     */
    private String replaceVariables(String rawSql, Map<String, String> vars) {
        String result = rawSql;
        for (Map.Entry<String, String> e : vars.entrySet()) {
            String key = e.getKey();
            String value = e.getValue();
            result = result.replace("${" + key + "}", value);
        }
        return result;
    }

    /**
     * 简单 SQL 分句
     * 说明：按分号拆分，并跳过空行、注释行。
     */
    private List<String> splitSqlStatements(String sqlContent) {
        List<String> list = new ArrayList<>();
        String[] arr = sqlContent.split(";");
        for (String part : arr) {
            String s = part == null ? "" : part.trim();
            if (s.isEmpty()) {
                continue;
            }
            if (s.startsWith("--") || s.startsWith("---") || s.startsWith("/*")) {
                continue;
            }
            list.add(s);
        }
        return list;
    }

    /**
     * 解析当前应用对应数据库配置
     */
    private DatabaseConfig resolveDbConfig(DeployConfig deployConfig, String app) {
        if (deployConfig == null || deployConfig.getDatabases() == null || deployConfig.getDatabases().isEmpty()) {
            return null;
        }
        int index = "cadre".equalsIgnoreCase(app) ? 1 : 0;
        if (deployConfig.getDatabases().size() <= index) {
            return null;
        }
        return deployConfig.getDatabases().get(index);
    }

    /**
     * 统计行数
     */
    private int countLines(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        return s.split("\\R", -1).length;
    }

    /**
     * 获取首个非空值
     */
    private String firstNotBlank(String a, String b) {
        if (!isBlank(a)) {
            return a.trim();
        }
        if (!isBlank(b)) {
            return b.trim();
        }
        return "";
    }

    private String valueOf(Map<String, String> map, String key) {
        return map == null ? null : map.get(key);
    }

    private String nvl(String s) {
        return s == null ? "" : s;
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private String abbreviate(String s, int maxLen) {
        if (s == null) {
            return "";
        }
        if (s.length() <= maxLen) {
            return s;
        }
        return s.substring(0, maxLen) + "...";
    }

    /**
     * 将应用标识映射到模板子目录
     * unified -> tyzc
     * cadre   -> gbgl
     */
    private String resolveAppDirName(String app) {
        return "cadre".equalsIgnoreCase(app) ? "gbgl" : "tyzc";
    }

    /**
     * SQL 字符串字面量转义
     */
    private String sqlStr(String s) {
        return "'" + nvl(s).replace("'", "''") + "'";
    }

    /**
     * URL 拼接工具：base + path，自动处理斜杠
     */
    private String buildUrlPath(String base, String path) {
        String b = nvl(base).trim();
        String p = nvl(path).trim();
        if (b.isEmpty()) {
            return p;
        }
        // 规则：若服务器 URL 未携带协议，则默认补全为 http://
        if (!b.matches("(?i)^https?://.*")) {
            b = "http://" + b;
        }
        b = b.replaceAll("/+$", "");
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        return b + p;
    }

    /**
     * 应用初始化项
     */
    private static class AppInitItem {
        private final String key;
        private final String appId;
        private final String appCode;
        private final String appName;
        private final String ssoPath;
        private final String apiPath;

        private AppInitItem(String key, String appId, String appCode, String appName, String ssoPath, String apiPath) {
            this.key = key;
            this.appId = appId;
            this.appCode = appCode;
            this.appName = appName;
            this.ssoPath = ssoPath;
            this.apiPath = apiPath;
        }
    }
}
