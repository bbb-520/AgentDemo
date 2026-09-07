package com.bbb.exercise.agentdemo1_0.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * AI 装配中心（由原 {@code LlmConfig} 演进并更名）。
 *
 * <p><b>职责收敛</b>：本项目与 Spring AI / LLM / 提示词相关的一切 Bean 统一在此装配，
 * AgentRunner 等业务类不再感知「文件读取、Api 构建、选项组装」等细节：
 * <ul>
 *     <li>{@link #chatMemory()}：会话记忆（滑动窗口），供记忆顾问与 AgentRunner 共用；</li>
 *     <li>{@link #systemPrompt(Resource)}：<b>系统提示词 Bean</b> —— 集中在此读取
 *         {@code classpath:system_prompt}，避免在 AgentRunner 构造器中做文件 IO；</li>
 *     <li>{@link #qwenChatModel(OpenAiProperties)}：底层 OpenAI 兼容 {@link OpenAiChatModel}。
 *         AgentRunner 的「非流式决策 + 答案回放」两阶段循环直接使用该 Bean 驱动
 *         模型 → 工具 → 再入模型 的轮转；默认选项来自配置属性（{@link OpenAiProperties}）；</li>
 *     <li>{@link #agentAdvisorChain(ChatMemory)}：<b>可复用的默认 Advisor 链</b>（日志 +
 *         会话记忆）。作为独立 Bean 暴露，同步 {@link ChatClient} 挂载于此；若将来需要
 *         自建其它 ChatClient（例如无工具的精简客户端）或手动构建 Prompt，可原样复用；</li>
 *     <li>{@link #qwenChatClient(OpenAiChatModel, List, List, String)}：高层 ChatClient，
 *         <b>默认系统提示 + 默认 Advisor + 默认工具</b>一次性配好，同步调用路径开箱即用。</li>
 * </ul>
 *
 * <p>工具回调（{@code List<ToolCallback>}）由 {@link ToolConfig} 自动扫描注册：
 * 任何含 {@code @Tool} 方法的 Bean 都会被自动发现，本类不关心具体有哪些工具。
 */
@Configuration
@EnableConfigurationProperties(OpenAiProperties.class)
public class AiConfiguration {

    /** 内存聊天记忆（滑动窗口），用于保存对话上下文 */
    @Bean
    public ChatMemory chatMemory() {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(50)
                .build();
    }

    /**
     * 系统提示词 Bean：集中读取 classpath 资源并缓存为 String。
     *
     * <p>原实现把「读文件」放在 AgentRunner 的 Spring 构造器中（{@code throws IOException}、
     * 双重构造器只为测试注入文本）；此处上移为配置 Bean 后，AgentRunner 仅声明式注入
     * {@code String systemPrompt}，读写失败在启动期即暴露（fail-fast），不再推迟到首次对话。
     */
    @Bean
    public String systemPrompt(@Value("classpath:system_prompt") Resource systemPromptResource) {
        try {
            return systemPromptResource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取系统提示词失败: classpath:system_prompt", e);
        }
    }

    /** 底层 OpenAI 兼容 ChatModel（决策轮/答案轮直接使用） */
    @Bean
    public OpenAiChatModel qwenChatModel(OpenAiProperties properties) {
        OpenAiApi api = OpenAiApi.builder()
                .apiKey(properties.getApiKey())
                .baseUrl(properties.getBaseUrl())
                .completionsPath("/chat/completions")
                .build();

        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder()
                .model(properties.getChat().getOptions().getModel());
        // 可选默认选项：仅当配置显式给出时才覆盖服务端默认值
        Double temperature = properties.getChat().getOptions().getTemperature();
        if (temperature != null) {
            options.temperature(temperature);
        }

        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(options.build())
                .build();
    }

    /**
     * 可复用的默认 Advisor 链（日志 + 会话记忆）。
     *
     * <p>提取为独立 Bean 的意义：同步 {@link ChatClient} 通过 defaultAdvisors 挂载；
     * 若未来新增「无记忆」或「无日志」的精简客户端，或需要手动执行 Advisor 管线，
     * 均以本链为唯一事实来源，避免各 Bean 各自 new 导致行为分叉。
     */
    @Bean
    public List<Advisor> agentAdvisorChain(ChatMemory chatMemory) {
        return List.of(
                new SimpleLoggerAdvisor(),
                MessageChatMemoryAdvisor.builder(chatMemory).build());
    }

    /**
     * 高层 ChatClient（同步/阻塞路径使用）：默认系统提示 + 默认 Advisor + 默认工具。
     *
     * <p>调用方无需再重复 {@code .system(...)} / {@code .tools(...)}；
     * 会话记忆顾问通过请求参数 {@code ChatMemory.CONVERSATION_ID} 定位会话。
     */
    @Bean
    public ChatClient qwenChatClient(OpenAiChatModel qwenChatModel,
                                     List<Advisor> agentAdvisorChain,
                                     List<ToolCallback> agentToolCallbacks,
                                     String systemPrompt) {
        return ChatClient.builder(qwenChatModel)
                .defaultSystem(systemPrompt)
                .defaultAdvisors(agentAdvisorChain)
                .defaultToolCallbacks(agentToolCallbacks)
                .build();
    }
}
