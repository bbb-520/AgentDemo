package com.bbb.exercise.agentdemo1_0.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OpenAI 兼容端点配置属性（绑定 {@code application.yml} 的 {@code spring.ai.openai.*} 段）。
 *
 * <p><b>重构动机</b>：原 {@code LlmConfig} 用三个散落的 {@code @Value} 字段承载
 * api-key / base-url / model，并在 Java 代码里手写 {@code OpenAiChatOptions}。
 * 迁移到本类后，<b>默认选项成为一等配置</b>——在 yml 中可见、可改（例如换模型、
 * 固定温度），装配代码只面向本类编程，不再散落字符串 key，也为将来接入官方
 * {@code spring-ai-spring-boot-starter}（其自动装配同样读取该前缀）留好平滑迁移路径。
 *
 * <p>注意：本项目<b>手动构建</b> {@code OpenAiChatModel}（指向 DashScope compatible-mode），
 * 因此 yml 中的选项不会像 starter 那样被自动套用，而是经本类被
 * {@link AiConfiguration} 显式装配为模型的 defaultOptions。
 *
 * @author agentDemo1_0
 * @see AiConfiguration
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "spring.ai.openai")
public class OpenAiProperties {

    /** OpenAI API 密钥（生产环境建议经环境变量注入，勿明文入库） */
    private String apiKey;

    /** OpenAI 兼容端点地址（本项目为阿里百炼 DashScope compatible-mode） */
    private String baseUrl;

    /** 聊天相关默认选项 */
    private Chat chat = new Chat();

    @Getter
    @Setter
    public static class Chat {

        /** 每次请求的默认选项 */
        private Options options = new Options();
    }

    @Getter
    @Setter
    public static class Options {

        /** 默认模型标识 */
        private String model = "qwen-turbo";

        /** 采样温度；null 表示不设置、交给服务端默认值 */
        private Double temperature;
    }
}
