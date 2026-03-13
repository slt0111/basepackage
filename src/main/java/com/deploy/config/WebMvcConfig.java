package com.deploy.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring MVC 配置
 * 说明：注册访问日志拦截器，统一记录接口调用信息。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    /**
     * 说明：通过配置开关控制是否启用访问日志，默认开启。
     * application.yml 中可配置 logging.access.enabled=false 关闭。
     */
    @Value("${logging.access.enabled:true}")
    private boolean accessLogEnabled;

    private final AccessLogInterceptor accessLogInterceptor = new AccessLogInterceptor();

    /**
     * 说明：将访问日志拦截器加入 Spring MVC 拦截器链。
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        if (accessLogEnabled) {
            // 说明：这里只拦截后端 API 前缀，避免对静态资源（如 HTML/JS/CSS/图片等）记录访问日志。
            registry.addInterceptor(accessLogInterceptor)
                    .addPathPatterns("/api/**");
        }
    }
}

