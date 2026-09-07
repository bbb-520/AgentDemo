package com.bbb.exercise.agentdemo1_0.tools;

import com.bbb.exercise.agentdemo1_0.tools.weather.weatherTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.bbb.exercise.agentdemo1_0.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;

/**
 * Tavily {@code /search} 调用的薄壳，封装天气/景点两个工具的公共部分：
 * <ul>
 *     <li>统一组装请求体（{@code query + search_depth + max_results + include_answer}）；</li>
 *     <li>解析响应：优先取 {@code answer} 字段，缺失则回退拼接 {@code results[].content}；</li>
 *     <li>统一错误抛出（HTTP 4xx/5xx、Tavily 无内容），保持上层工具的「原始异常 → 工具失败事件」契约。</li>
 * </ul>
 *
 * <p>工具类只关心业务：{@link weatherTool#getWeather}、{@link attractionTool#getAttraction}
 * 各自把请求字符串拼好交给本类，避免 Tavily 调用样板代码重复。
 */
@Slf4j
@Component
public class TavilySearcher {

    private static final String SEARCH_PATH = "/search";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final WebClient tavilyWebClient;

    @Value("${tavily.search-depth}")
    private String searchDepth;

    @Value("${tavily.max-results}")
    private int maxResults;

    @Value("${tavily.include-answer}")
    private boolean includeAnswer;

    public TavilySearcher(@Qualifier("tavilyWebClient") WebClient tavilyWebClient) {
        this.tavilyWebClient = tavilyWebClient;
    }

    /**
     * 向 Tavily 发起一次搜索，优先返回 AI 摘要 {@code answer}；缺失时拼接
     * {@code results[].content} 兜底；二者皆空时抛 {@link IllegalStateException}。
     *
     * @param context 业务上下文，写入日志便于排查（建议：{@code 工具名:关键参数}）
     * @param query   Tavily 搜索字符串
     * @return Tavily 返回的可读文本
     */
    public String search(String context, String query) {
        Map<String, Object> body = Map.of(
                "query", query,
                "search_depth", searchDepth,
                "max_results", maxResults,
                "include_answer", includeAnswer);
        log.debug("Tavily 调用, ctx={}, query={}", context, query);

        try {
            JsonNode response = tavilyWebClient.post()
                    .uri(SEARCH_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, resp -> resp.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .map(bodyText -> new IllegalStateException(
                                    "Tavily 返回错误: HTTP " + resp.statusCode() + ", body=" + bodyText)))
                    .bodyToMono(JsonNode.class)
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
