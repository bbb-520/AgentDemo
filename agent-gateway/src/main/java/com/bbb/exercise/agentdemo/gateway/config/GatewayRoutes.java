package com.bbb.exercise.agentdemo.gateway.config;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GatewayRoutes {
    @Bean
    RouteLocator agentRoutes(RouteLocatorBuilder builder) {
        return builder.routes()
                .route("auth", r -> r.path("/api/auth/**").uri("lb://agent-auth-service"))
                .route("chat", r -> r.path("/api/chat/**").uri("lb://agent-chat-service"))
                .route("images", r -> r.path("/api/images/**", "/api/image-jobs/**").uri("lb://agent-media-service"))
                .route("content", r -> r.path("/api/bobo-world/**", "/api/zines/**", "/api/photos/**").uri("lb://agent-content-service"))
                .route("orchestrator", r -> r.path("/api/agent-runs/**").uri("lb://agent-orchestrator-service"))
                .build();
    }
}
