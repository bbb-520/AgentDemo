package com.bbb.exercise.agentdemo1_0.config;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI 装配重构的集成回归测试。
 *
 * <p>守护本次重构的关键契约：
 * <ol>
 *     <li>系统提示词由 {@link AiConfiguration} 读取并暴露为 String Bean
 *         （AgentRunner 构造器不再做文件 IO，若读取失败应在启动期 fail-fast）；</li>
 *     <li>{@code OpenAiChatModel} 的 defaultOptions 来自配置属性绑定
 *         （{@code spring.ai.openai.chat.options.model} → qwen-turbo）；</li>
 *     <li>ChatClient / ChatMemory 等装配 Bean 在容器中齐全。</li>
 * </ol>
 */
@SpringBootTest
class AiConfigurationTest {

    @Autowired
    private String systemPrompt;

    @Autowired
    private ChatClient qwenChatClient;

    @Autowired
    private OpenAiChatModel qwenChatModel;

    @Autowired
    private ChatMemory chatMemory;

    @Test
    void systemPrompt_isLoadedAsBeanWithToolGuidance() {
        // 由配置中心读取 classpath:system_prompt，包含工具清单与「思考：」规则
        assertThat(systemPrompt).isNotBlank()
                .contains("getWeather")
                .contains("getAttraction")
                .contains("思考：");
    }

    @Test
    void qwenChatModel_defaultOptions_boundFromConfigurationProperties() {
        // 默认选项不再散落在 @Value + Java 硬编码，而是经 OpenAiProperties 绑定 yml
        assertThat(qwenChatModel.getDefaultOptions())
                .isInstanceOf(OpenAiChatOptions.class)
                .satisfies(options -> assertThat(((OpenAiChatOptions) options).getModel()).isEqualTo("qwen-turbo"));
    }

    @Test
    void beans_areFullyAssembled() {
        assertThat(qwenChatClient).isNotNull();
        assertThat(chatMemory).isNotNull();
    }
}
