package com.bbb.exercise.agentdemo1_0.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.client.webflux.transport.WebClientStreamableHttpTransport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MCP Server 端到端回归：用 Spring AI / MCP SDK 的**客户端**跑一遍宿主视角的全链路。
 *
 * <p>等价于宿主（WorkBuddy / Claude Desktop 等）会做的事：
 * {@code initialize} → {@code tools/list} → {@code tools/call}。
 * 之所以用测试而不是外部脚本，是为了让「工具能被宿主调用」这件事进 CI、可回归，
 * 且不引入额外运行时依赖——{@code mcp-spring-webflux} 与 {@code mcp-core} 已由
 * MCP Server starter 传递引入。
 *
 * <p>注意：{@code WebClientStreamableHttpTransport} 的 {@code endpoint} 是**路径**，
 * 域名端口由传入的 {@link WebClient.Builder} 的 baseUrl 决定。
 */
@Slf4j
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpServerIntegrationTest {

    /** 工具内部最长阻塞约 40s（Open-Meteo 10s 超时 + Tavily 30s 降级），客户端上限给足 */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(90);

    private static final String PROTOCOL_PATH = "/mcp";

    @LocalServerPort
    private int port;

    /** 以宿主身份连上本进程暴露的 MCP Server */
    private McpSyncClient connectAsHost() {
        WebClient.Builder webClientBuilder = WebClient.builder()
                .baseUrl("http://127.0.0.1:" + port);
        WebClientStreamableHttpTransport transport = WebClientStreamableHttpTransport
                .builder(webClientBuilder)
                .endpoint(PROTOCOL_PATH)
                .build();
        return McpClient.sync(transport)
                .clientInfo(new McpSchema.Implementation("agentdemo1_0-integration-test", "1.0.0"))
                .requestTimeout(REQUEST_TIMEOUT)
                .initializationTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Test
    @DisplayName("宿主握手成功，并能看到全部已注册工具")
    void host_canHandshakeAndListTools() {
        try (McpSyncClient client = connectAsHost()) {
            client.initialize();

            assertThat(client.isInitialized()).isTrue();

            McpSchema.Implementation serverInfo = client.getServerInfo();
            assertThat(serverInfo.name()).isEqualTo("bbb-mcp-server");
            assertThat(serverInfo.version()).isEqualTo("1.0.0");

            // 工具集说明要下发给宿主，否则宿主侧的模型不知道该怎么组合这两个工具
            assertThat(client.getServerInstructions()).contains("getWeather", "getAttraction");

            // 只声明 tool 能力：没有实现 prompts / resources 就不该对外广告
            assertThat(client.getServerCapabilities().tools()).isNotNull();
            assertThat(client.getServerCapabilities().prompts()).isNull();
            assertThat(client.getServerCapabilities().resources()).isNull();

            List<McpSchema.Tool> tools = client.listTools().tools();
            List<String> names = tools.stream().map(McpSchema.Tool::name).toList();
            assertThat(names).containsExactlyInAnyOrder("getWeather", "getAttraction");
            log.info("[mcp-it] 宿主可见工具: {}", names);

            // 入参 Schema 要完整，宿主才能正确组装参数
            McpSchema.Tool weather = tools.stream()
                    .filter(tool -> "getWeather".equals(tool.name()))
                    .findFirst()
                    .orElseThrow();
            assertThat(weather.description()).isNotBlank();
            assertThat(schemaPropertyNames(weather)).contains("city");
            assertThat(requiredProperties(weather)).contains("city");
        }
    }

    @Test
    @DisplayName("宿主可以真正调用天气工具并拿到结果")
    void host_canCallWeatherTool() {
        try (McpSyncClient client = connectAsHost()) {
            client.initialize();

            McpSchema.CallToolResult result = client.callTool(
                    new McpSchema.CallToolRequest("getWeather", Map.of("city", "杭州")));

            assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);

            String text = textOf(result);
            log.info("[mcp-it] getWeather 返回: {}", text);
            assertThat(text).isNotBlank();
            assertThat(text).containsIgnoringCase("杭州");
        }
    }

    @Test
    @DisplayName("入参非法时工具报错但服务端不崩，仍能继续响应")
    void invalidArgument_isReportedAsToolError() {
        try (McpSyncClient client = connectAsHost()) {
            client.initialize();

            // city 必填，这里故意给空串，命中 AssertUtils.isNotBlank
            McpSchema.CallToolResult failed = client.callTool(
                    new McpSchema.CallToolRequest("getWeather", Map.of("city", " ")));
            assertThat(failed.isError()).isEqualTo(Boolean.TRUE);
            log.info("[mcp-it] 非法入参被正确拒绝: {}", textOf(failed));

            // 关键：一次失败不应把会话打坏
            McpSchema.CallToolResult ok = client.callTool(
                    new McpSchema.CallToolRequest("getWeather", Map.of("city", "北京")));
            assertThat(ok.isError()).isNotEqualTo(Boolean.TRUE);
            assertThat(textOf(ok)).isNotBlank();
        }
    }

    /** 拼出工具返回的所有文本内容 */
    private static String textOf(McpSchema.CallToolResult result) {
        return result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(McpSchema.TextContent.class::cast)
                .map(McpSchema.TextContent::text)
                .collect(Collectors.joining(System.lineSeparator()));
    }

    /** 取出 inputSchema.properties 的字段名 */
    @SuppressWarnings("unchecked")
    private static List<String> schemaPropertyNames(McpSchema.Tool tool) {
        Object properties = tool.inputSchema().get("properties");
        if (!(properties instanceof Map<?, ?> map)) {
            return List.of();
        }
        return ((Map<String, Object>) map).keySet().stream().toList();
    }

    /** 取出 inputSchema.required */
    @SuppressWarnings("unchecked")
    private static List<String> requiredProperties(McpSchema.Tool tool) {
        Object required = tool.inputSchema().get("required");
        return required instanceof List<?> list ? (List<String>) list : List.of();
    }
}
