package com.bbb.exercise.agentdemo1_0.agent;

import com.bbb.exercise.agentdemo1_0.enums.ChatEventTypeEnum;
import com.bbb.exercise.agentdemo1_0.vo.ChatEventVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatcher;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AgentRunner#stream(String, String)} 直播事件序列的单测（两阶段架构版）。
 *
 * <p>两阶段架构下，工具决策轮走<b>非流式</b> {@code ChatModel#call}，
 * 最终答案以 DATA 分块回放。Mock 掉 {@link ChatModel} 与 {@link ToolCallback}，
 * 不依赖真实 LLM / 网络，守护：
 * 「SESSION_INFO 先行 → REASONING 剥离 → TOOL_CALL_STARTED → TOOL_CALL_RESULT
 * → DATA(回放) → USAGE」的事件序列、工具失败兜底、非法参数自愈循环、
 * 跨轮 token 汇总与会话记忆回写。
 */
class AgentRunnerTest {

    private ChatClient chatClient;
    private ChatModel chatModel;
    private ChatMemory chatMemory;
    private ToolCallback weatherCallback;
    private AgentRunner runner;

    @BeforeEach
    void setUp() {
        chatClient = mock(ChatClient.class);
        chatModel = mock(ChatModel.class);
        chatMemory = mock(ChatMemory.class);
        weatherCallback = mock(ToolCallback.class);

        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn("getWeather");
        when(weatherCallback.getToolDefinition()).thenReturn(definition);
        when(weatherCallback.call(anyString()))
                .thenReturn("北京晴，26℃，微风 3 级。湿度 40%。");

        when(chatMemory.get(anyString())).thenReturn(List.of());

        runner = new AgentRunner(chatClient, chatModel, chatMemory,
                List.of(weatherCallback), "你是测试助手，可调用工具获取实时信息。");
    }

    @Test
    void stream_emitsSessionReasoningToolResultDataAndUsageInOrder() {
        // 决策轮 1：模型输出「思考：」行并声明调用 getWeather（非流式，参数必然完整）
        ChatResponse decision1 = response("思考：需要先查询北京的实时天气。\n", usage(5, 7),
                new AssistantMessage.ToolCall("call-1", "function", "getWeather", "{\"city\":\"北京\"}"));
        // 决策轮 2：拿到工具结果后输出最终答案
        ChatResponse decision2 = response("北京今天晴，26℃，适合去颐和园。", usage(6, 9));

        when(chatModel.call(any(Prompt.class))).thenReturn(decision1, decision2);

        List<ChatEventVO> events = runner.stream("北京天气怎么样？", "chat-ut1").collectList().block();

        assertThat(events).isNotNull();
        // 1) 流首必须是一条 SESSION_INFO
        assertThat(events.get(0).getEventType()).isEqualTo(ChatEventTypeEnum.SESSION_INFO.getValue());
        @SuppressWarnings("unchecked")
        Map<String, Object> sessionData = (Map<String, Object>) events.get(0).getEventData();
        assertThat(sessionData).containsEntry("conversationId", "chat-ut1");

        // 2) 真实「思考：」行被剥离为 REASONING
        int reasoningIdx = indexOfType(events, ChatEventTypeEnum.REASONING);
        assertThat(reasoningIdx).isGreaterThanOrEqualTo(0);
        assertThat(events.get(reasoningIdx).getEventData()).isEqualTo("需要先查询北京的实时天气。");

        // 3) REASONING 在 TOOL_CALL_STARTED 之前；真实推理存在时不再自动补推理
        int startedIdx = indexOfType(events, ChatEventTypeEnum.TOOL_CALL_STARTED);
        assertThat(startedIdx).isGreaterThan(reasoningIdx);
        assertThat(countOfType(events, ChatEventTypeEnum.REASONING)).isEqualTo(1);

        // 4) 工具调用开始：携带工具名与参数
        @SuppressWarnings("unchecked")
        Map<String, Object> startedData = (Map<String, Object>) events.get(startedIdx).getEventData();
        assertThat(startedData).containsEntry("toolName", "getWeather");
        assertThat(startedData.get("arguments").toString()).contains("北京");

        // 5) 工具调用结果
        int resultIdx = indexOfType(events, ChatEventTypeEnum.TOOL_CALL_RESULT);
        assertThat(resultIdx).isGreaterThan(startedIdx);
        @SuppressWarnings("unchecked")
        Map<String, Object> resultData = (Map<String, Object>) events.get(resultIdx).getEventData();
        assertThat(resultData).containsEntry("toolName", "getWeather");
        assertThat(resultData.get("result").toString()).contains("北京晴");

        // 6) 最终答案以 DATA 事件（分块回放）到达，拼接后为完整答案
        int dataIdx = indexOfType(events, ChatEventTypeEnum.DATA);
        assertThat(dataIdx).isGreaterThan(resultIdx);
        assertThat(allData(events)).contains("适合去颐和园");

        // 7) 末尾 USAGE：跨轮 token 汇总（5+6 / 7+9）
        int usageIdx = indexOfType(events, ChatEventTypeEnum.USAGE);
        assertThat(usageIdx).isEqualTo(events.size() - 1);
        @SuppressWarnings("unchecked")
        Map<String, Object> usageData = (Map<String, Object>) events.get(usageIdx).getEventData();
        assertThat(usageData).containsEntry("promptTokens", 11L);
        assertThat(usageData).containsEntry("completionTokens", 16L);
        assertThat(usageData).containsEntry("totalTokens", 27L);

        // 8) 工具以模型下发的参数 JSON 被调用一次
        verify(weatherCallback).call("{\"city\":\"北京\"}");
        // 9) 会话记忆只回写「用户问题 + 最终助手回答」各一次
        ArgumentMatcher<List<Message>> persisted = messages -> messages != null && messages.size() == 2
                && messages.get(0) instanceof UserMessage
                && messages.get(1) instanceof AssistantMessage assistant
                && assistant.getText().contains("适合去颐和园");
        verify(chatMemory, times(1)).add(eq("chat-ut1"), argThat(persisted));
    }

    @Test
    void stream_toolFailure_emitsFailedEventAndContinuesToFinalAnswer() {
        // 工具抛异常：不中断整条流，发出 TOOL_CALL_FAILED 后模型基于错误文本兜底
        when(weatherCallback.call(anyString()))
                .thenThrow(new RuntimeException("Tavily 查询超时"));

        ChatResponse decision1 = response(null, usage(0, 0),
                new AssistantMessage.ToolCall("call-9", "function", "getWeather", "{\"city\":\"上海\"}"));
        ChatResponse decision2 = response("抱歉，天气服务暂时不可用，请稍后再试。", usage(3, 5));
        when(chatModel.call(any(Prompt.class))).thenReturn(decision1, decision2);

        List<ChatEventVO> events = runner.stream("上海天气？", "chat-ut2").collectList().block();

        assertThat(events).isNotNull();
        // 模型没有输出真实推理行 → 自动补一条固定格式 REASONING
        assertThat(countOfType(events, ChatEventTypeEnum.REASONING)).isEqualTo(1);
        int reasoningIdx = indexOfType(events, ChatEventTypeEnum.REASONING);
        assertThat(events.get(reasoningIdx).getEventData()).isEqualTo("调用工具：getWeather");

        // TOOL_CALL_FAILED 携带错误信息
        int failedIdx = indexOfType(events, ChatEventTypeEnum.TOOL_CALL_FAILED);
        assertThat(failedIdx).isGreaterThan(indexOfType(events, ChatEventTypeEnum.TOOL_CALL_STARTED));
        @SuppressWarnings("unchecked")
        Map<String, Object> failedData = (Map<String, Object>) events.get(failedIdx).getEventData();
        assertThat(failedData).containsEntry("toolName", "getWeather");
        assertThat(failedData.get("error").toString()).contains("Tavily 查询超时");

        // 失败后流继续，最终仍有 DATA 兜底回答与 USAGE 收尾
        assertThat(allData(events)).contains("天气服务暂时不可用");
        assertThat(events.get(events.size() - 1).getEventType()).isEqualTo(ChatEventTypeEnum.USAGE.getValue());
    }

    @Test
    void stream_directAnswerWithoutTools_noToolEventsAndMemoryPersisted() {
        ChatResponse decision = response("北京四季分明，春秋最佳。", usage(8, 4));
        when(chatModel.call(any(Prompt.class))).thenReturn(decision);

        List<ChatEventVO> events = runner.stream("北京适合什么时候去？", "chat-ut3").collectList().block();

        assertThat(events).isNotNull();
        assertThat(events.get(0).getEventType()).isEqualTo(ChatEventTypeEnum.SESSION_INFO.getValue());
        assertThat(countOfType(events, ChatEventTypeEnum.TOOL_CALL_STARTED)).isZero();
        assertThat(countOfType(events, ChatEventTypeEnum.TOOL_CALL_RESULT)).isZero();
        assertThat(countOfType(events, ChatEventTypeEnum.REASONING)).isZero();
        assertThat(allData(events)).isEqualTo("北京四季分明，春秋最佳。");
        assertThat(events.get(events.size() - 1).getEventType()).isEqualTo(ChatEventTypeEnum.USAGE.getValue());

        // 工具从未被调用，决策只走了一轮
        verify(weatherCallback, never()).call(anyString());
        verify(chatModel, times(1)).call(any(Prompt.class));
        // 会话记忆仍被回写
        ArgumentMatcher<List<Message>> persisted = messages -> messages != null && messages.size() == 2;
        verify(chatMemory, times(1)).add(eq("chat-ut3"), argThat(persisted));
    }

    /**
     * 自愈循环：决策轮参数虽必然完整（非流式），但仍保留防御——
     * 若模型给出非法 JSON（如 max_tokens 截断），不调用工具、发 FAILED，
     * 并把错误作为 ToolResponse 回传，模型下一轮修正参数后工具正常执行。
     */
    @Test
    void stream_invalidArguments_selfHealsByFeedingErrorBackToModel() {
        ChatResponse badDecision = response(null, usage(0, 0),
                new AssistantMessage.ToolCall("call-bad", "function", "getWeather", "{\"city"));
        ChatResponse goodDecision = response(null, usage(5, 7),
                new AssistantMessage.ToolCall("call-good", "function", "getWeather", "{\"city\":\"北京\"}"));
        ChatResponse answer = response("北京今天晴，26℃。", usage(6, 9));
        when(chatModel.call(any(Prompt.class))).thenReturn(badDecision, goodDecision, answer);

        List<ChatEventVO> events = runner.stream("北京天气？", "chat-ut4").collectList().block();

        // 非法参数：STARTED + FAILED 各一次（坏的那次），RESULT 一次（好的那次）
        assertThat(countOfType(events, ChatEventTypeEnum.TOOL_CALL_STARTED)).isEqualTo(2);
        assertThat(countOfType(events, ChatEventTypeEnum.TOOL_CALL_FAILED)).isEqualTo(1);
        assertThat(countOfType(events, ChatEventTypeEnum.TOOL_CALL_RESULT)).isEqualTo(1);
        // 脏 JSON 绝不进入 Spring AI 反序列化层
        verify(weatherCallback, never()).call("{\"city");
        verify(weatherCallback, times(1)).call("{\"city\":\"北京\"}");
        // 决策共三轮：坏参数 → 修正 → 答案
        verify(chatModel, times(3)).call(any(Prompt.class));
        // 最终答案正常输出
        assertThat(allData(events)).contains("北京今天晴");
        assertThat(events.get(events.size() - 1).getEventType()).isEqualTo(ChatEventTypeEnum.USAGE.getValue());
    }

    /**
     * 自愈的载体：非法参数那一轮回传给模型的消息里，必须包含与 tool_call
     * 一一对应的 {@link ToolResponseMessage}（否则 OpenAI 协议直接 400），
     * 且错误文本要引导模型重新调用。
     */
    @Test
    void stream_invalidArguments_appendsMatchingToolResponseMessage() {
        ChatResponse badDecision = response(null, usage(0, 0),
                new AssistantMessage.ToolCall("call-bad", "function", "getWeather", "{\"city"));
        ChatResponse answer = response("北京今天晴。", usage(3, 5));
        when(chatModel.call(any(Prompt.class))).thenReturn(badDecision, answer);

        runner.stream("北京天气？", "chat-ut5").collectList().block();

        // 第二轮决策的 Prompt 中应包含 ToolResponseMessage（错误回传）
        org.mockito.ArgumentCaptor<Prompt> captor = org.mockito.ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel, times(2)).call(captor.capture());
        Prompt secondRound = captor.getAllValues().get(1);
        boolean hasToolResponse = secondRound.getInstructions().stream()
                .anyMatch(m -> m instanceof ToolResponseMessage);
        assertThat(hasToolResponse).isTrue();
    }

    /**
     * 同一轮声明多个工具调用：全部按序执行，结果一一对应回传。
     */
    @Test
    void stream_multipleToolCallsInOneRound_allExecutedInOrder() {
        ChatResponse decision1 = response(null, usage(5, 7),
                new AssistantMessage.ToolCall("call-a", "function", "getWeather", "{\"city\":\"北京\"}"),
                new AssistantMessage.ToolCall("call-b", "function", "getWeather", "{\"city\":\"上海\"}"));
        ChatResponse decision2 = response("北京晴，上海也晴。", usage(6, 9));
        when(chatModel.call(any(Prompt.class))).thenReturn(decision1, decision2);

        List<ChatEventVO> events = runner.stream("北京和上海天气？", "chat-ut6").collectList().block();

        assertThat(countOfType(events, ChatEventTypeEnum.TOOL_CALL_STARTED)).isEqualTo(2);
        assertThat(countOfType(events, ChatEventTypeEnum.TOOL_CALL_RESULT)).isEqualTo(2);
        assertThat(countOfType(events, ChatEventTypeEnum.TOOL_CALL_FAILED)).isZero();
        verify(weatherCallback).call("{\"city\":\"北京\"}");
        verify(weatherCallback).call("{\"city\":\"上海\"}");
        assertThat(allData(events)).contains("北京晴，上海也晴");
    }

    /**
     * 纯单元验证 {@link AgentRunner#isCompleteToolCall(AssistantMessage.ToolCall)}：
     * 不依赖 ChatModel 桩件，直接覆盖 4 种典型脏数据 + 1 种完整态。
     */
    @Test
    void isCompleteToolCall_filtersIncompleteShapes() {
        assertThat(AgentRunner.isCompleteToolCall(null)).isFalse();
        assertThat(AgentRunner.isCompleteToolCall(
                new AssistantMessage.ToolCall(null, "function", "getWeather", "{\"city\":\"北京\"}"))).isFalse();
        assertThat(AgentRunner.isCompleteToolCall(
                new AssistantMessage.ToolCall("c1", "function", null, "{\"city\":\"北京\"}"))).isFalse();
        assertThat(AgentRunner.isCompleteToolCall(
                new AssistantMessage.ToolCall("c2", "function", "getWeather", null))).isFalse();
        assertThat(AgentRunner.isCompleteToolCall(
                new AssistantMessage.ToolCall("c3", "function", "getWeather", "{\"cit"))).isFalse();
        assertThat(AgentRunner.isCompleteToolCall(
                new AssistantMessage.ToolCall("c4", "function", "getWeather", ""))).isFalse();
        // 完整 JSON 才通过
        assertThat(AgentRunner.isCompleteToolCall(
                new AssistantMessage.ToolCall("c5", "function", "getWeather", "{\"city\":\"北京\"}"))).isTrue();
    }

    // ---------- 工具方法 ----------

    private static int indexOfType(List<ChatEventVO> events, ChatEventTypeEnum type) {
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i).getEventType() == type.getValue()) {
                return i;
            }
        }
        return -1;
    }

    private static long countOfType(List<ChatEventVO> events, ChatEventTypeEnum type) {
        return events.stream().filter(e -> e.getEventType() == type.getValue()).count();
    }

    /** 拼接全部 DATA 事件文本（答案分块回放后的完整内容） */
    private static String allData(List<ChatEventVO> events) {
        return events.stream()
                .filter(e -> e.getEventType() == ChatEventTypeEnum.DATA.getValue())
                .map(e -> e.getEventData() == null ? "" : e.getEventData().toString())
                .reduce("", (a, b) -> a + b);
    }

    private static Usage usage(int promptTokens, int completionTokens) {
        Usage usage = mock(Usage.class);
        when(usage.getPromptTokens()).thenReturn(promptTokens);
        when(usage.getCompletionTokens()).thenReturn(completionTokens);
        return usage;
    }

    /** 构造一轮非流式决策响应：文本（可空）+ 工具调用声明（可零个） */
    private static ChatResponse response(String text, Usage usage, AssistantMessage.ToolCall... calls) {
        AssistantMessage message = new AssistantMessage(text == null ? "" : text, Map.of(), List.of(calls));
        return new ChatResponse(List.of(new Generation(message)), metadata(usage));
    }

    private static ChatResponseMetadata metadata(Usage usage) {
        return ChatResponseMetadata.builder()
                .id("test-" + System.nanoTime())
                .model("qwen")
                .usage(usage)
                .build();
    }
}
