package com.bbb.exercise.agentdemo.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class AuthRelayGlobalFilter implements GlobalFilter, Ordered {
    private static final String USER_ID = "X-User-Id";
    private static final String TENANT_ID = "X-Tenant-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String userId = exchange.getRequest().getHeaders().getFirst(USER_ID);
        String tenantId = exchange.getRequest().getHeaders().getFirst(TENANT_ID);
        ServerHttpRequest.Builder request = exchange.getRequest().mutate();
        if (userId != null && !userId.isBlank()) request.header(USER_ID, userId);
        if (tenantId != null && !tenantId.isBlank()) request.header(TENANT_ID, tenantId);
        return chain.filter(exchange.mutate().request(request.build()).build());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
