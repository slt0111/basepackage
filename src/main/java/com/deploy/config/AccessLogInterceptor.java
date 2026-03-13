package com.deploy.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 接口访问日志拦截器
 * 说明：统一记录每次 HTTP 调用的基本信息（不打印 body），方便开发调试和问题排查。
 */
public class AccessLogInterceptor implements HandlerInterceptor {

    /**
     * 说明：使用独立 logger，方便在日志配置中单独控制访问日志级别/输出位置。
     */
    private static final Logger log = LoggerFactory.getLogger("ACCESS_LOG");

    /**
     * 说明：请求开始前记录起始时间和基本信息。
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 说明：将当前时间戳放入 request attribute，供 afterCompletion 计算耗时使用。
        request.setAttribute("_access_log_start_time", System.currentTimeMillis());
        if (log.isInfoEnabled()) {
            String method = request.getMethod();
            String uri = request.getRequestURI();
            String query = request.getQueryString();
            String clientIp = resolveClientIp(request);
            // 说明：不打印 body，只记录方法、路径、查询参数与客户端 IP。
            log.info("[access-log] -> method={} uri={} query={} clientIp={}", method, uri, query, clientIp);
        }
        return true;
    }

    /**
     * 说明：请求完成后记录状态码与耗时（无论是否发生异常都会进入该回调）。
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, @Nullable Exception ex) {
        Long start = (Long) request.getAttribute("_access_log_start_time");
        long costMs = 0L;
        if (start != null) {
            costMs = System.currentTimeMillis() - start;
        }
        if (log.isInfoEnabled()) {
            String method = request.getMethod();
            String uri = request.getRequestURI();
            int status = response.getStatus();
            String error = ex != null ? ex.getMessage() : "";
            log.info("[access-log] <- method={} uri={} status={} costMs={} error={}", method, uri, status, costMs, error);
        }
    }

    /**
     * 说明：从常见的反向代理头中解析客户端 IP，若不存在则回退到 remoteAddr。
     */
    private String resolveClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip != null && !ip.isEmpty()) {
            int commaIndex = ip.indexOf(',');
            return commaIndex > 0 ? ip.substring(0, commaIndex).trim() : ip.trim();
        }
        ip = request.getHeader("X-Real-IP");
        if (ip != null && !ip.isEmpty()) {
            return ip.trim();
        }
        return request.getRemoteAddr();
    }
}

