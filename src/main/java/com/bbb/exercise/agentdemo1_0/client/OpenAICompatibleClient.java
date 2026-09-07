package com.bbb.exercise.agentdemo1_0.client;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容客户端：可连接任意遵循 OpenAI Chat Completions 协议的 LLM 服务商。
 *
 * <p>实例化需要三个信息：API_KEY、BASE_URL、MODEL_ID，
 * 具体取值取决于所使用的服务商（当前默认使用 Qwen，详见 {@code LlmConfig}）。
 *
 * <p>TODO: 让用户自主选择服务商（OpenAI / DeepSeek / 本地 Ollama ...），
 * 届时只需提供对应服务商的 apiKey + baseUrl + modelId 即可，无需改动本类。
 */
public class QpenAICompatibleClient {

    private static final String CHAT_PATH = "/chat/completions";
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final WebClient webClient;
    private final String modelId;

    //通过构造函数注入配置，可连接不同的 LLM 服务商
    public QpenAICompatibleClient(String apiKey, String baseUrl, String modelId) {
        if (apiKey == null || apiKey.isBlank()
                || baseUrl == null || baseUrl.isBlank()
                || modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("apiKey、baseUrl、modelId 均不能为空");
        }
        this.modelId = modelId;
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    /**
     * 单轮对话（仅用户消息）
     */
    public String generate(String userMessage) {
        return chat(List.of(new Message("user", userMessage)));
    }

    /**
     * 带系统提示词的对话
     */
    public String chatWithSystem(String systemPrompt, String userMessage) {
        return chat(List.of(
                new Message("system", systemPrompt),
                new Message("user", userMessage)));
    }

    /**
     * 多轮对话
     *
     * @param messages 按时间顺序排列的消息列表
     * @return 助手回复内容
     */
    public String chat(List<Message> messages) {
        Map<String, Object> requestBody = Map.of(
                "model", modelId,
                "messages", messages,
                "temperature", 0.7,
                "stream", false);

        //构造POST请求以及检错
        JsonNode response = webClient.post()
                .uri(CHAT_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .retrieve()
                .onStatus(HttpStatusCode::isError, resp -> resp.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(body -> new IllegalStateException(
                                "LLM 调用失败: HTTP " + resp.statusCode() + ", body=" + body)))
                .bodyToMono(JsonNode.class)
                .block(TIMEOUT);

        if (response == null) {
            throw new IllegalStateException("LLM 未返回任何内容");
        }

        JsonNode choices = response.path("choices");
        if (choices.isMissingNode() || !choices.isArray() || choices.size() == 0) {
            throw new IllegalStateException("LLM 响应缺少 choices: " + response);
        }
        return choices.get(0).path("message").path("content").asText("").trim();
    }

    /**
     * OpenAI 消息结构
     *
     * @param role    "system" / "user" / "assistant"
     * @param content 消息正文
     */
    public record Message(String role, String content) {
    }
}
