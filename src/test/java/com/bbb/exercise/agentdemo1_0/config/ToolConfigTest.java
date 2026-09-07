package com.bbb.exercise.agentdemo1_0.config;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具自动注册的集成回归测试。
 *
 * <p>背景：曾因把 {@code Class} 误传给 {@code AopUtils.getTargetClass(Object)}
 * （该方法接收实例而非 Class），导致自动扫描结果为 0 个工具 —— 纯单测全部
 * mock 掉 {@code List<ToolCallback>} 无法暴露，真实启动后 Agent 将无任何工具可用。
 * 本测试直接守护「容器装配出的工具回调非空且包含既有工具」。
 */
@SpringBootTest
class ToolConfigTest {

    @Autowired
    private List<ToolCallback> toolCallbacks;

    @Test
    void autoScan_registersAllAnnotatedToolBeans() {
        List<String> names = toolCallbacks.stream()
                .map(c -> c.getToolDefinition() == null ? null : c.getToolDefinition().name())
                .toList();
        assertThat(names).contains("getWeather", "getAttraction");
    }
}
