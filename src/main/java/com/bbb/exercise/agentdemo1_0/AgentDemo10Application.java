package com.bbb.exercise.agentdemo1_0;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 应用启动类。
 *
 * <p>技术栈：Spring Boot 4 + WebFlux（全响应式，故不能再引入 spring-boot-starter-web）+
 * Spring AI 2.0（OpenAI 兼容端点，本项目指向阿里百炼 qwen）+ Redis 会话记忆。
 *
 * <p>对外提供两组入口：
 * <ul>
 *   <li>{@code /api/chat} —— 业务对话（SSE 流式）；</li>
 *   <li>{@code /mcp} —— MCP Server，把 {@code @Tool} 工具注册给宿主（WorkBuddy / Claude Desktop 等）调用。</li>
 * </ul>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class AgentDemo10Application {

    public static void main(String[] args) {
        SpringApplication.run(AgentDemo10Application.class, args);
    }

}
