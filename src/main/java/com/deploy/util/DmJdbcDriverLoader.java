package com.deploy.util;

import com.deploy.websocket.DeployLogWebSocket;
import org.springframework.boot.system.ApplicationHome;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Driver;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 达梦 JDBC 驱动动态加载器
 * 说明：由于达梦驱动通常以外部 jar 形式提供（放在 lib/ 目录），该工具会在运行时将 lib/*.jar 加载到上下文类加载器中，
 *      以支持无需重新打包即可使用达梦连接能力（schema 拉取/导出等）。
 */
public class DmJdbcDriverLoader {

    /**
     * 常见达梦驱动类名候选
     * 说明：按优先级排列，首先尝试 DmJdbcDriver18 对应的类（与你指定的 DmJdbcDriver18.jar 匹配），
     *      其次才回退到旧版 DmDriver / com.dameng.DmDriver。
     */
    public static final String[] DM_DRIVER_CLASSES = {
            "dm.jdbc.driver.DmDriver18",   // 优先使用 DmJdbcDriver18.jar
            "dm.jdbc.driver.DmDriver",
            "com.dameng.DmDriver"
    };

    private static volatile boolean attempted = false;
    /**
     * 共享 ClassLoader
     * 说明：动态从 lib/ 加载驱动 jar 后，需要跨请求线程复用该 ClassLoader，
     *      否则会出现“schema 能加载，但 objects 又找不到驱动”的现象。
     */
    private static volatile URLClassLoader sharedLoader = null;

    private DmJdbcDriverLoader() {
    }

    /**
     * 确保驱动类可被加载
     * 说明：
     * - 先尝试直接 Class.forName；
     * - 若失败，则尝试从 lib/ 目录动态加载 jar，再次尝试 Class.forName；
     * - 动态加载成功后会显式向 DriverManager 注册 Driver，避免出现 “No suitable driver”。
     */
    public static void ensureDmDriverLoaded() throws ClassNotFoundException {
        // 优先复用共享 loader（跨线程复用）
        if (sharedLoader != null) {
            Thread.currentThread().setContextClassLoader(sharedLoader);
            if (tryLoadAnyDriver(sharedLoader)) {
                ensureDriverRegistered("jdbc:dm://127.0.0.1:5236");
                return;
            }
        }

        // 先试一次：classpath 已包含驱动则无需动态加载
        if (tryLoadAnyDriver(Thread.currentThread().getContextClassLoader())) {
            // 说明：部分环境下即使类可加载，也可能未触发 DriverManager 注册；这里尝试补注册一次（幂等）。
            ensureDriverRegistered("jdbc:dm://127.0.0.1:5236");
            return;
        }

        // 标记已尝试过扫描，但不再对后续线程直接短路抛错（避免跨线程 ContextClassLoader 差异导致误判）。
        attempted = true;

        List<Path> jars = findLibJars();
        if (jars.isEmpty()) {
            // 说明：jar 运行时“lib 目录存在但仍扫描不到”通常是目录基准不一致（jar 所在目录 vs 当前工作目录）。
            // 这里输出候选路径与应用位置，便于现场直接定位。
            logLibScanEvidence(Collections.emptyList());
            throw lastDriverNotFound();
        }

        try {
            List<URL> urls = new ArrayList<>();
            for (Path p : jars) {
                urls.add(p.toUri().toURL());
            }
            ClassLoader parent = Thread.currentThread().getContextClassLoader();
            sharedLoader = new URLClassLoader(urls.toArray(new URL[0]), parent);
            Thread.currentThread().setContextClassLoader(sharedLoader);

            DeployLogWebSocket.sendLog("[mock-export] 已加载达梦驱动 jar（lib 目录）数量=" + jars.size());

            if (!tryLoadAnyDriver(sharedLoader)) {
                // 说明：找到了 jar 但仍无法加载类，通常是 jar 非达梦驱动或版本不匹配；输出 jar 列表便于核对。
                logLibScanEvidence(jars);
                throw lastDriverNotFound();
            }
            // 说明：URLClassLoader 加载的 Driver 默认不一定会被 DriverManager 自动发现/注册，这里显式注册。
            ensureDriverRegistered("jdbc:dm://127.0.0.1:5236");
        } catch (Exception e) {
            ClassNotFoundException cnf = lastDriverNotFound();
            cnf.addSuppressed(e);
            throw cnf;
        }
    }

    private static boolean tryLoadAnyDriver(ClassLoader loader) {
        for (String cls : DM_DRIVER_CLASSES) {
            try {
                Class.forName(cls, true, loader);
                return true;
            } catch (ClassNotFoundException ignored) {
            }
        }
        return false;
    }

    /**
     * 确保 DriverManager 已注册可用的达梦 Driver
     * 说明：解决动态加载 jar 后 DriverManager 仍提示 “No suitable driver found” 的问题。
     *
     * @param probeUrl 用于 acceptsURL 探测的 jdbc url（任意合法 dm url 格式即可）
     */
    public static void ensureDriverRegistered(String probeUrl) throws ClassNotFoundException {
        try {
            // 先判断是否已有 driver 接受该 URL（已有则无需重复注册）
            java.util.Enumeration<Driver> drivers = DriverManager.getDrivers();
            while (drivers.hasMoreElements()) {
                Driver d = drivers.nextElement();
                try {
                    if (probeUrl != null && d.acceptsURL(probeUrl)) {
                        return;
                    }
                } catch (Exception ignored) {
                }
            }

            // 显式实例化并注册（按候选类名依次尝试）
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            for (String cls : DM_DRIVER_CLASSES) {
                try {
                    Class<?> c = Class.forName(cls, true, cl);
                    Object o = c.getDeclaredConstructor().newInstance();
                    if (o instanceof Driver) {
                        DriverManager.registerDriver(new DriverShim((Driver) o));
                        DeployLogWebSocket.sendLog("[mock-export] 已向 DriverManager 注册达梦驱动: " + cls);
                        return;
                    }
                } catch (ClassNotFoundException e) {
                    // try next
                }
            }
        } catch (Exception e) {
            ClassNotFoundException cnf = lastDriverNotFound();
            cnf.addSuppressed(e);
            throw cnf;
        }

        throw lastDriverNotFound();
    }

    private static ClassNotFoundException lastDriverNotFound() {
        // 说明：把扫描证据拼进异常消息，确保即使看不到 WebSocket 日志，也能从接口返回直接定位路径问题。
        String diag = buildLibScanDiagnostics();
        return new ClassNotFoundException("无法加载达梦数据库驱动，请确保达梦 JDBC 驱动 jar 在 lib/ 目录下或已打入 classpath。尝试的驱动类: "
                + String.join(", ", DM_DRIVER_CLASSES) + (diag.isEmpty() ? "" : "。诊断信息: " + diag));
    }

    /**
     * 查找 lib/*.jar
     * 说明：优先以应用目录（JAR 所在目录）为基准，其次回溯项目根目录（IDE 场景）。
     */
    private static List<Path> findLibJars() {
        List<Path> jars = new ArrayList<>();
        for (Path base : candidateLibDirs()) {
            if (base == null) continue;
            try {
                if (!Files.exists(base) || !Files.isDirectory(base)) {
                    continue;
                }
                Files.list(base)
                        .filter(p -> Files.isRegularFile(p) && p.getFileName().toString().toLowerCase().endsWith(".jar"))
                        .forEach(jars::add);
            } catch (Exception ignored) {
            }
        }
        return jars;
    }

    private static List<Path> candidateLibDirs() {
        List<Path> dirs = new ArrayList<>();

        // 0) 显式指定：优先使用系统属性/环境变量指定的 lib 目录（用于 jar 部署目录结构不固定的场景）
        // 支持：
        // - JVM 参数：-Ddm.jdbc.libDir=/opt/app/lib
        // - 环境变量：DM_JDBC_LIB_DIR=/opt/app/lib
        Path explicit = explicitLibDir();
        if (explicit != null) {
            dirs.add(explicit);
        }

        // 1) JAR 部署：应用目录同级 lib/
        ApplicationHome home = new ApplicationHome(DmJdbcDriverLoader.class);
        File appDir = home.getDir();
        if (appDir != null) {
            dirs.add(new File(appDir, "lib").toPath());

            // 说明：部分部署会将 jar 放在子目录（如 target/ 或 app/），但 lib/ 放在上一级目录。
            // 例如：/opt/app/bin/app.jar + /opt/app/lib/*.jar
            File parent = appDir.getParentFile();
            if (parent != null) {
                dirs.add(new File(parent, "lib").toPath());
            }

            // 2) IDE 场景：target/classes -> 回溯项目根目录的 lib/
            String path = appDir.getAbsolutePath();
            if (path.endsWith("target" + File.separator + "classes")) {
                File targetDir = appDir.getParentFile();
                File projectRoot = (targetDir != null ? targetDir.getParentFile() : null);
                if (projectRoot != null) {
                    dirs.add(new File(projectRoot, "lib").toPath());
                }
            }
        }

        // 3) 兜底：当前工作目录 lib/
        dirs.add(new File("lib").toPath());

        return dirs;
    }

    /**
     * 读取显式指定的 lib 目录
     */
    private static Path explicitLibDir() {
        try {
            String p = System.getProperty("dm.jdbc.libDir");
            if (p == null || p.trim().isEmpty()) {
                p = System.getenv("DM_JDBC_LIB_DIR");
            }
            if (p == null || p.trim().isEmpty()) {
                return null;
            }
            return Paths.get(p.trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 构造 lib 扫描诊断信息（用于异常消息）
     */
    private static String buildLibScanDiagnostics() {
        try {
            ApplicationHome home = new ApplicationHome(DmJdbcDriverLoader.class);
            File dir = home.getDir();
            File src = home.getSource();
            StringBuilder sb = new StringBuilder();
            sb.append("home.dir=").append(dir != null ? dir.getAbsolutePath() : "null");
            sb.append(", home.source=").append(src != null ? src.getAbsolutePath() : "null");
            sb.append(", user.dir=").append(System.getProperty("user.dir"));

            Path explicit = explicitLibDir();
            if (explicit != null) {
                sb.append(", explicitLibDir=").append(explicit.toAbsolutePath());
            }

            List<Path> candidates = candidateLibDirs();
            sb.append(", candidateLibDirs=[");
            for (int i = 0; i < candidates.size(); i++) {
                Path c = candidates.get(i);
                if (i > 0) sb.append(" | ");
                sb.append(c != null ? c.toAbsolutePath() : "null");
                sb.append(" exists=").append(c != null && Files.exists(c));
                sb.append(" isDir=").append(c != null && Files.isDirectory(c));
            }
            sb.append("]");
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 输出 lib 扫描证据（候选目录/工作目录/jar 所在目录/找到的 jar 列表）
     * 说明：用于现场快速确认 “lib 实际放在哪” 与 “程序在按什么路径找”。
     */
    private static void logLibScanEvidence(List<Path> jars) {
        try {
            ApplicationHome home = new ApplicationHome(DmJdbcDriverLoader.class);
            File dir = home.getDir();
            File src = home.getSource();
            DeployLogWebSocket.sendLog("[mock-export] 达梦驱动加载诊断: home.dir=" + (dir != null ? dir.getAbsolutePath() : "null")
                    + " home.source=" + (src != null ? src.getAbsolutePath() : "null")
                    + " user.dir=" + System.getProperty("user.dir"));
            List<Path> candidates = candidateLibDirs();
            for (Path c : candidates) {
                DeployLogWebSocket.sendLog("[mock-export] 达梦驱动加载诊断: candidateLibDir=" + (c != null ? c.toAbsolutePath() : "null")
                        + " exists=" + (c != null && Files.exists(c))
                        + " isDir=" + (c != null && Files.isDirectory(c)));
            }
            if (jars != null && !jars.isEmpty()) {
                for (Path p : jars) {
                    DeployLogWebSocket.sendLog("[mock-export] 达梦驱动加载诊断: foundJar=" + p.toAbsolutePath());
                }
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Driver 代理（Shim）
     * 说明：避免 DriverManager 对不同 ClassLoader 加载的 Driver 进行权限过滤导致不可用。
     */
    private static class DriverShim implements Driver {
        private final Driver driver;

        DriverShim(Driver driver) {
            this.driver = driver;
        }

        @Override
        public boolean acceptsURL(String url) throws java.sql.SQLException {
            return driver.acceptsURL(url);
        }

        @Override
        public java.sql.Connection connect(String url, java.util.Properties info) throws java.sql.SQLException {
            return driver.connect(url, info);
        }

        @Override
        public int getMajorVersion() {
            return driver.getMajorVersion();
        }

        @Override
        public int getMinorVersion() {
            return driver.getMinorVersion();
        }

        @Override
        public java.sql.DriverPropertyInfo[] getPropertyInfo(String url, java.util.Properties info) throws java.sql.SQLException {
            return driver.getPropertyInfo(url, info);
        }

        @Override
        public boolean jdbcCompliant() {
            return driver.jdbcCompliant();
        }

        @Override
        public java.util.logging.Logger getParentLogger() {
            // 说明：Java 7+ 接口方法；达梦驱动若不支持可能抛异常，这里做最小实现。
            try {
                return driver.getParentLogger();
            } catch (Exception e) {
                return java.util.logging.Logger.getLogger("global");
            }
        }
    }
}

