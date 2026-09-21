package com.bbb.exercise.agentdemo1_0.chat;

import com.bbb.exercise.agentdemo1_0.auth.UserApiKeyService.UserApiKeys;
import com.bbb.exercise.agentdemo1_0.tools.TavilySearcher;
import com.bbb.exercise.agentdemo1_0.tools.UserAttractionTool;
import com.bbb.exercise.agentdemo1_0.tools.weather.UserWeatherTool;
import com.bbb.exercise.agentdemo1_0.tools.weather.WeatherService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Builds a request-scoped ChatClient so each account uses its own encrypted key. */
@Component
public class UserChatClientFactory {
    private final String systemPrompt;
    private final ChatMemory chatMemory;
    private final WeatherService weatherService;
    private final TavilySearcher tavilySearcher;
    private final String baseUrl;
    private final String model;

    public UserChatClientFactory(String systemPrompt, ChatMemory chatMemory,
                                 WeatherService weatherService, TavilySearcher tavilySearcher,
                                 @Value("${spring.ai.openai.base-url}") String baseUrl,
                                 @Value("${spring.ai.openai.chat.model:qwen-turbo}") String model) {
        this.systemPrompt = systemPrompt;
        this.chatMemory = chatMemory;
        this.weatherService = weatherService;
        this.tavilySearcher = tavilySearcher;
        this.baseUrl = baseUrl;
        this.model = model;
    }

    public ChatClient create(UserApiKeys keys) {
        if (!keys.hasQwen() || !keys.hasTavily()) {
            throw new IllegalStateException("请先在设置中同时配置 Qwen 和 Tavily API 密钥");
        }
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .apiKey(keys.qwenApiKey()).baseUrl(baseUrl).model(model)
                .timeout(Duration.ofSeconds(60)).maxRetries(2).build();
        OpenAiChatModel chatModel = OpenAiChatModel.builder().options(options).build();
        var tools = MethodToolCallbackProvider.builder()
                .toolObjects(new UserWeatherTool(weatherService, keys), new UserAttractionTool(tavilySearcher, keys))
                .build();
        return ChatClient.builder(chatModel)
                .defaultSystem(systemPrompt)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .defaultToolCallbacks(tools.getToolCallbacks())
                .build();
    }
}
