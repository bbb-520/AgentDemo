package com.bbb.exercise.agentdemo.gateway.filter;

import com.bbb.exercise.agentdemo.common.trace.RequestId;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class RequestIdGlobalFilter implements GlobalFilter, Ordered {
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String requestId = RequestId.resolve(exchange.getRequest().getHeaders().getFirst(RequestId.HEADER));
        ServerHttpRequest request = exchange.getRequest().mutate().header(RequestId.HEADER, requestId).build();
        exchange.getResponse().getHeaders().set(RequestId.HEADER, requestId);
        return chain.filter(exchange.mutate().request(request).build());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
