package com.bbb.exercise.agentdemo1_0.chat;

import com.bbb.exercise.agentdemo1_0.auth.UserApiKeyService.UserApiKeys;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Builds an image-focused request-scoped ChatClient using the user's encrypted key. */
@Component
public class UserChatClientFactory {
    private final String systemPrompt;
    private final ChatMemory chatMemory;
    private final String baseUrl;
    private final String model;
    private final String visionModel;

    public UserChatClientFactory(String systemPrompt, ChatMemory chatMemory,
                                 @Value("${spring.ai.openai.base-url}") String baseUrl,
                                 @Value("${spring.ai.openai.chat.model:qwen-turbo}") String model,
                                 @Value("${spring.ai.openai.chat.vision-model:qwen-vl-plus}") String visionModel) {
        this.systemPrompt = systemPrompt;
        this.chatMemory = chatMemory;
        this.baseUrl = baseUrl;
        this.model = model;
        this.visionModel = visionModel;
    }

    public ChatClient create(UserApiKeys keys, boolean multimodal) {
        if (!keys.hasQwen()) throw new IllegalStateException("请先在用户页配置阿里云 API Key");
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .apiKey(keys.qwenApiKey())
                .baseUrl(baseUrl)
                .model(multimodal ? visionModel : model)
                .timeout(Duration.ofSeconds(60))
                .maxRetries(2)
                .build();
        OpenAiChatModel chatModel = OpenAiChatModel.builder().options(options).build();
        return ChatClient.builder(chatModel)
                .defaultSystem(systemPrompt)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }
}
