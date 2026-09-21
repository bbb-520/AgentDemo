package com.bbb.exercise.agentdemo1_0.identity;

import com.bbb.exercise.agentdemo1_0.config.AppProperties;
import com.bbb.exercise.agentdemo1_0.auth.AuthService;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.security.Principal;
import java.time.Duration;
import java.util.UUID;

/**
 * 从认证主体或服务端签发的匿名 Cookie 解析身份。
 *
 * <p>当前工程未接入 Spring Security，因此本地实验使用 HttpOnly Cookie；生产环境接入
 * Security 后，WebFlux Principal 会优先于匿名 Cookie，客户端不能通过请求体伪造归属。</p>
 */
@Component
public class ChatIdentityResolver {

    private final AppProperties properties;
    private final AuthService authService;

    public ChatIdentityResolver(AppProperties properties, AuthService authService) {
        this.properties = properties;
        this.authService = authService;
    }

    public Mono<ChatIdentity> resolve(ServerWebExchange exchange) {
        return Mono.fromCallable(() -> authService.resolve(exchange))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .filter(java.util.Objects::nonNull)
                .switchIfEmpty(exchange.getPrincipal()
                .map(Principal::getName)
                .filter(name -> name != null && !name.isBlank())
                .map(name -> new ChatIdentity(properties.getSecurity().getDefaultTenantId(), name, true))
                .switchIfEmpty(Mono.fromSupplier(() -> resolveAnonymous(exchange))));
    }

    public Mono<ChatIdentity> resolveRequired(ServerWebExchange exchange) {
        return resolve(exchange).flatMap(identity -> identity.authenticated()
                ? Mono.just(identity)
                : Mono.error(new AuthService.AuthException(401, "请先登录")));
    }

    private ChatIdentity resolveAnonymous(ServerWebExchange exchange) {
        String anonymousCookieName = properties.getSecurity().getAnonymousCookieName();
        var cookie = exchange.getRequest().getCookies().getFirst(anonymousCookieName);
        String userId = cookie == null ? null : cookie.getValue();
        if (!isUuid(userId)) {
            userId = UUID.randomUUID().toString();
            exchange.getResponse().addCookie(ResponseCookie.from(anonymousCookieName, userId)
                    .httpOnly(true)
                    // Netlify 与 API 使用不同站点时，跨源 fetch + credentials 需要 None/Secure。
                    // 本地 HTTP 仍使用 Lax，避免开发环境被浏览器拒绝。
                    .sameSite(isSecureRequest(exchange) ? "None" : "Lax")
                    .secure(isSecureRequest(exchange))
                    .path("/")
                    .maxAge(Duration.ofDays(30))
                    .build());
        }
        return new ChatIdentity(properties.getSecurity().getDefaultTenantId(), "anonymous:" + userId, false);
    }

    private static boolean isSecureRequest(ServerWebExchange exchange) {
        String forwardedProto = exchange.getRequest().getHeaders().getFirst("X-Forwarded-Proto");
        return "https".equalsIgnoreCase(forwardedProto)
                || "https".equalsIgnoreCase(exchange.getRequest().getURI().getScheme())
                || Boolean.parseBoolean(System.getenv().getOrDefault("FORCE_COOKIE_SECURE", "false"));
    }

    private static boolean isUuid(String value) {
        if (value == null) {
            return false;
        }
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }
}
