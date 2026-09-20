package com.bbb.exercise.agentdemo1_0.config;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具注册的集成回归测试。
 *
 * <p>背景：曾因把 {@code Class} 误传给工具回调构建逻辑（而非实例），导致注册结果为 0 个工具 ——
 * 纯单测全部 mock 掉工具回调无法暴露，真实启动后 Agent 与 MCP 宿主都将无工具可用。
 *
 * <p>工具注册处现为 {@link AiConfig#agentToolCallbackProvider}：这一个 Bean 同时供
 * 本地对话（ChatClient）与 MCP 服务端（{@code /mcp}）消费，因此只要它非空且包含既有工具，
 * 两条链路就都有工具可用。本测试直接守护这一装配结果。
 */
@SpringBootTest
class ToolRegistrationTest {

    @Autowired
    private ToolCallbackProvider toolCallbackProvider;

    @Test
    void registeredTools_containExistingTools() {
        List<String> names = Arrays.stream(toolCallbackProvider.getToolCallbacks())
                .map(callback -> callback.getToolDefinition() == null
                        ? null : callback.getToolDefinition().name())
                .toList();
        assertThat(names).contains("getWeather", "getAttraction");
    }
}
