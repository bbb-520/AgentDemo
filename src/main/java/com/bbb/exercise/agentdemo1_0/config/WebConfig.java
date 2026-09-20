package com.bbb.exercise.agentdemo1_0.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.reactive.config.WebFluxConfigurer;

import java.util.List;
import java.util.Arrays;


/**
 * WebFlux 跨域配置：允许本机前端（Vite 开发服务器等）直连后端接口。
 */
@Configuration
public class WebConfig implements WebFluxConfigurer {

    private static final List<String> ALLOWED_ORIGIN_PATTERNS = allowedOrigins();

    private static List<String> allowedOrigins() {
        String configured = System.getenv("CORS_ALLOWED_ORIGINS");
        if (configured == null || configured.isBlank()) {
            return List.of("http://localhost:*", "http://127.0.0.1:*");
        }
        List<String> origins = Arrays.stream(configured.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
        return origins.isEmpty()
                ? List.of("http://localhost:*", "http://127.0.0.1:*")
                : origins;
    }

    /** 注册响应式 CORS 过滤器，规则作用于 {@code /api/**} */
    @Bean
    public CorsWebFilter corsWebFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(ALLOWED_ORIGIN_PATTERNS);
        config.setAllowedMethods(List.of("GET", "POST", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("Content-Type"));
        // /api/chat 使用 HttpOnly 匿名 Cookie 绑定会话归属；跨域前端必须携带凭据。
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return new CorsWebFilter(source);
    }
}
