package com.bbb.exercise.agentdemo1_0.tools;

import tools.jackson.databind.JsonNode;
import com.bbb.exercise.agentdemo1_0.utils.StringUtils;
import com.bbb.exercise.agentdemo1_0.config.TavilyProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;


/**
 * Tavily 联网搜索封装（被 {@code attractionTool} 与 {@code TavilyWeatherProvider} 复用）。
 *
 * <p>复用 {@link com.bbb.exercise.agentdemo1_0.config.TavilyConfig} 提供的
 * {@code tavilyWebClient} 发起 POST {@code /search}，解析优先取 {@code answer} 字段，
 * 缺失时退化为拼接 {@code results[].content}。
 */
@Slf4j
@Component
public class TavilySearcher {

    private static final String SEARCH_PATH = "/search";
    /** 单次搜索的阻塞等待上限 */
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final WebClient tavilyWebClient;
    private final WebClient.Builder webClientBuilder;

    private final TavilyProperties properties;

    public TavilySearcher(@Qualifier("tavilyWebClient") WebClient tavilyWebClient,
                          WebClient.Builder webClientBuilder,
                          TavilyProperties properties) {
        this.tavilyWebClient = tavilyWebClient;
        this.webClientBuilder = webClientBuilder;
        this.properties = properties;
    }


    /**
     * 执行一次搜索。
     *
     * @param context 仅用于日志定位（例如 {@code weather:北京}）
     * @param query   实际检索语句
     * @return 搜索结果文本（answer 或 results 正文拼接）
     * @throws RuntimeException 请求失败或返回内容为空
     */
    public String search(String context, String query) {
        return searchWithClient(tavilyWebClient, context, query);
    }

    /** Per-user search. The key stays inside the backend. */
    public String search(String apiKey, String context, String query) {
        if (StringUtils.isBlank(apiKey)) throw new IllegalStateException("未配置 Tavily API 密钥");
        WebClient client = webClientBuilder.clone()
                .baseUrl(properties.getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
        return searchWithClient(client, context, query);
    }

    private String searchWithClient(WebClient client, String context, String query) {
        // Map.of 不允许 null 值：先显式校验，避免以 NPE 的形式暴露给上层工具
        if (StringUtils.isBlank(query)) {
            throw new IllegalArgumentException("Tavily 搜索内容不能为空, ctx=" + context);
        }
        Map<String, Object> body = Map.of(
                "query", query,
                "search_depth", properties.getSearchDepth(),
                "max_results", properties.getMaxResults(),
                "include_answer", properties.isIncludeAnswer());
        log.debug("Tavily 调用, ctx={}, query={}", context, query);

        try {
            JsonNode response = client.post()
                    .uri(SEARCH_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    // HTTP 4xx/5xx 统一转成带响应体的异常，便于定位
                    .onStatus(HttpStatusCode::isError, resp -> resp.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .map(bodyText -> new IllegalStateException(
                                    "Tavily 返回错误: HTTP " + resp.statusCode() + ", body=" + bodyText)))
                    .bodyToMono(JsonNode.class)
                    // 工具方法是同步签名，此处阻塞等待（调用发生在 boundedElastic 之外的模型回调线程）
                    .block(TIMEOUT);
            return parse(context, response);
        } catch (Exception e) {
            log.error("Tavily 调用失败, ctx={}", context, e);
            throw e instanceof RuntimeException re ? re : new IllegalStateException("Tavily 调用失败: " + context, e);
        }
    }

    /** 解析：先 {@code answer}、再 {@code results[].content} 拼接，二者都空则抛错 */
    private String parse(String context, JsonNode response) {
        if (response == null) {
            throw new IllegalStateException("Tavily 未返回任何内容, ctx=" + context);
        }
        String answer = response.path("answer").asText(null);
        if (answer != null && !StringUtils.isBlank(answer)) {
            return StringUtils.trim(answer);
        }
        log.warn("Tavily 未返回 answer，改用搜索结果正文, ctx={}", context);
        StringBuilder fallback = new StringBuilder();
        for (JsonNode result : response.path("results")) {
            String content = result.path("content").asText("");
            if (!StringUtils.isBlank(content)) {
                fallback.append(StringUtils.trim(content)).append(System.lineSeparator());
            }
        }
        if (fallback.length() == 0) {
            throw new IllegalStateException("Tavily 未返回可用的内容, ctx=" + context);
        }
        return StringUtils.trim(fallback.toString());
    }
}
