package com.bbb.exercise.agentdemo1_0.auth;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;

    public AuthController(AuthService auth) { this.auth = auth; }

    @PostMapping("/register")
    public Mono<Map<String, Object>> register(@RequestBody Credentials body, ServerWebExchange exchange) {
        return Mono.fromCallable(() -> {
            AuthService.User user = auth.register(body.username(), body.password());
            return Map.<String, Object>of("username", user.username());
        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    @PostMapping("/login")
    public Mono<Map<String, Object>> login(@RequestBody Credentials body, ServerWebExchange exchange) {
        return Mono.fromCallable(() -> {
            AuthService.LoginResult result = auth.login(body.username(), body.password());
            exchange.getResponse().addCookie(cookie(exchange, result.token(), auth.sessionTtl()));
            return Map.<String, Object>of("username", result.user().username());
        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    @PostMapping("/logout")
    public Mono<Void> logout(ServerWebExchange exchange) {
        return Mono.fromRunnable(() -> {
            auth.logout(exchange);
            exchange.getResponse().addCookie(cookie(exchange, "", Duration.ZERO));
        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()).then();
    }

    @GetMapping("/me")
    public Mono<Map<String, Object>> me(ServerWebExchange exchange) {
        return Mono.fromCallable(() -> {
            var identity = auth.resolve(exchange);
            if (identity == null) throw new AuthService.AuthException(401, "未登录");
            return Map.<String, Object>of("authenticated", true, "username", auth.username(identity));
        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    private ResponseCookie cookie(ServerWebExchange exchange, String token, Duration ttl) {
        boolean secure = "https".equalsIgnoreCase(exchange.getRequest().getHeaders().getFirst("X-Forwarded-Proto"))
                || "https".equalsIgnoreCase(exchange.getRequest().getURI().getScheme())
                || Boolean.parseBoolean(System.getenv().getOrDefault("FORCE_COOKIE_SECURE", "false"));
        return ResponseCookie.from(auth.sessionCookieName(), token)
                .httpOnly(true).secure(secure).sameSite(secure ? "None" : "Lax")
                .path("/").maxAge(ttl).build();
    }

    public record Credentials(String username, String password) {}
}
