package com.bbb.exercise.agentdemo1_0.config;

import com.bbb.exercise.agentdemo1_0.tools.AttractionTool;
import com.bbb.exercise.agentdemo1_0.tools.weather.WeatherTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;


@Slf4j
@Configuration
public class AiConfig {

    /** 读取 classpath 下的 {@code system_prompt} 文件作为全局系统提示词 */
    @Bean
    public String systemPrompt(@Value("classpath:system_prompt") Resource systemPromptResource) {
        try {
            return systemPromptResource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取系统提示词失败: classpath:system_prompt", e);
        }
    }

    /**
     * 工具注册：显式声明本项目对外提供哪些工具（不再做全容器反射扫描）。
     *
     * <p>这一个 Bean 会被两处消费，保证「对话可用的工具」与「MCP 暴露的工具」永远一致：
     * <ul>
     *   <li>{@link #chatClient} —— 本地对话时由模型按需调用；</li>
     *   <li>MCP 的 {@code ToolCallbackConverterAutoConfiguration.syncTools(...)} ——
     *       把同一批工具注册到 {@code /mcp}，供宿主（WorkBuddy / Claude Desktop 等）调用。</li>
     * </ul>
     *
     * <p>新增工具时：在方法入参里注入对应 Bean，并加入 {@code toolObjects} 即可。
     */
    @Bean
    public ToolCallbackProvider agentToolCallbackProvider(WeatherTool weatherTool,
                                                          AttractionTool attractionTool) {
        ToolCallbackProvider provider = MethodToolCallbackProvider.builder()
                .toolObjects(weatherTool, attractionTool)
                .build();
        List<String> names = Arrays.stream(provider.getToolCallbacks())
                .map(callback -> callback.getToolDefinition() == null ? "?" : callback.getToolDefinition().name())
                .toList();
        log.info("[tools] 已注册工具 {} 个: {}", names.size(), names);
        return provider;
    }


    @Bean
    public ChatClient chatClient(ChatClient.Builder chatClientBuilder,
                                 String systemPrompt,
                                 ChatMemory chatMemory,
                                 ToolCallbackProvider agentToolCallbackProvider) {
        return chatClientBuilder
                .defaultSystem(systemPrompt)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .defaultTools(agentToolCallbackProvider)
                .build();
    }
}
