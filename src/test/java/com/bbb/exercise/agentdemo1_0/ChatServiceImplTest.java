package com.bbb.exercise.agentdemo1_0;

import com.bbb.exercise.agentdemo1_0.agent.AgentRunner;
import com.bbb.exercise.agentdemo1_0.enums.ChatEventTypeEnum;
import com.bbb.exercise.agentdemo1_0.service.ChatService;
import com.bbb.exercise.agentdemo1_0.service.impl.ChatServiceImpl;
import com.bbb.exercise.agentdemo1_0.utils.ConversationKeys;
import com.bbb.exercise.agentdemo1_0.vo.ChatEventVO;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 契约语义单测（BACKEND_REQUIREMENTS §5 / §6 + SSE 直播事件契约）。
 *
 * <p>Mock 掉 {@link AgentRunner}，不依赖 LLM / 网络 / Spring 容器：
 * 守护「空问题 → 1004 事件」「空流 → 恰好一个 1002」「新直播事件原样透传不过滤」
 * 「历史标签与过滤」「会话键规则」等对外行为。
 */
class ChatServiceImplTest {

    private final AgentRunner agentRunner = mock(AgentRunner.class);
    private final ChatService service = new ChatServiceImpl(agentRunner);

    @Test
    void emptyQuestion_stream_emitsSingleErrorEventWithoutStop() {
        List<ChatEventVO> events = service.chat("", "s1").collectList().block();

        assertThat(events).hasSize(1);
        assertThat(events.get(0).getEventType()).isEqualTo(ChatEventTypeEnum.ERROR.getValue());
        assertThat(events.get(0).getEventData()).isEqualTo("问题不能为空");
    }

    @Test
    void blankQuestion_stream_doesNotInvokeAgent() {
        when(agentRunner.stream(anyString(), anyString()))
                .thenThrow(new AssertionError("空问题不应调用 Agent"));

        List<ChatEventVO> events = service.chat("   ", null).collectList().block();

        assertThat(events).hasSize(1);
        assertThat(events.get(0).getEventType()).isEqualTo(ChatEventTypeEnum.ERROR.getValue());
    }

    @Test
    void emptyModelStream_stream_emitsExactlyOneStopEvent() {
        // Agent 流没有任何事件（例如模型未产生任何输出）→ 契约只允许补发恰好 1 个 1002
        when(agentRunner.stream(eq("北京适合玩几天？"), eq("chat-mem-1"))).thenReturn(Flux.empty());

        List<ChatEventVO> events = service.chat("北京适合玩几天？", "mem-1").collectList().block();

        assertThat(events).hasSize(1);
        assertThat(events.get(0).getEventType()).isEqualTo(ChatEventTypeEnum.STOP.getValue());
        assertThat(events.get(0).getEventData()).isNull();
    }

    @Test
    void newLiveEvents_stream_passesThroughUnfilteredAndAppendsStop() {
        // AgentRunner 新增的直播事件必须被 ChatServiceImpl 原样透传：不过滤、不改写，仅末尾补发 STOP
        ChatEventVO session = ChatEventVO.builder()
                .eventType(ChatEventTypeEnum.SESSION_INFO.getValue())
                .eventData(Map.of("conversationId", "chat-mem-1", "timestamp", 1725600000000L))
                .build();
        // eventData 为 null 的控制类事件同样不得被过滤
        ChatEventVO control = ChatEventVO.builder()
                .eventType(ChatEventTypeEnum.PARAM.getValue())
                .eventData(null)
                .build();
        ChatEventVO reasoning = ChatEventVO.builder()
                .eventType(ChatEventTypeEnum.REASONING.getValue())
                .eventData("需要先查询北京的实时天气")
                .build();
        ChatEventVO started = ChatEventVO.builder()
                .eventType(ChatEventTypeEnum.TOOL_CALL_STARTED.getValue())
                .eventData(Map.of("toolName", "getWeather", "arguments", "{\"city\":\"北京\"}"))
                .build();

        when(agentRunner.stream(eq("北京天气怎么样？"), eq("chat-mem-1")))
                .thenReturn(Flux.just(session, control, reasoning, started));

        List<ChatEventVO> events = service.chat("北京天气怎么样？", "mem-1").collectList().block();

        assertThat(events).hasSize(5);
        assertThat(events.get(0).getEventType()).isEqualTo(ChatEventTypeEnum.SESSION_INFO.getValue());
        assertThat(events.get(0).getEventData()).isEqualTo(Map.of("conversationId", "chat-mem-1", "timestamp", 1725600000000L));
        assertThat(events.get(1).getEventType()).isEqualTo(ChatEventTypeEnum.PARAM.getValue());
        assertThat(events.get(1).getEventData()).isNull();
        assertThat(events.get(2).getEventType()).isEqualTo(ChatEventTypeEnum.REASONING.getValue());
        assertThat(events.get(2).getEventData()).isEqualTo("需要先查询北京的实时天气");
        assertThat(events.get(3).getEventType()).isEqualTo(ChatEventTypeEnum.TOOL_CALL_STARTED.getValue());
        assertThat(events.get(3).getEventData()).isInstanceOf(Map.class);
        // 最后一个必须是服务层补发的 STOP
        assertThat(events.get(4).getEventType()).isEqualTo(ChatEventTypeEnum.STOP.getValue());
    }

    @Test
    void syncEmptyQuestion_returnsFriendlyTextNot4xx() {
        assertThat(service.chatSync("", "s1")).isEqualTo("问题不能为空");
        assertThat(service.chatSync(null, null)).isEqualTo("问题不能为空");
    }

    @Test
    void history_formatsUserAndAssistantWithChineseLabels() {
        List<org.springframework.ai.chat.messages.Message> messages = List.of(
                new UserMessage("北京天气怎么样？"),
                new AssistantMessage("北京今天晴，26℃。"));
        when(agentRunner.history("chat-s1")).thenReturn(messages);

        List<String> history = service.history("s1");

        assertThat(history).containsExactly("我: 北京天气怎么样？", "助手: 北京今天晴，26℃。");
    }

    @Test
    void history_filtersBlankMessages() {
        when(agentRunner.history("chat-s1")).thenReturn(List.of(
                new UserMessage(""),
                new AssistantMessage("   "),
                new AssistantMessage("这是有效答案")));

        List<String> history = service.history("s1");

        assertThat(history).containsExactly("助手: 这是有效答案");
    }

    @Test
    void stop_isIdempotentForAnySession() {
        // 会话不在生成也应幂等、不抛异常（契约 §5.3）
        service.stop(null);
        service.stop("s1");
        service.stop("s1");
    }

    @Test
    void clear_delegatesToAgentRunnerWithPrefixedKey() {
        service.clear("s1");
        verify(agentRunner).clearHistory("chat-s1");
    }

    @Test
    void conversationKeys_followContractRules() {
        // 契约 §2/§6：空/空白 → chat-default；非空 → chat-<sessionId> 并 trim
        assertThat(ConversationKeys.resolve(null)).isEqualTo("chat-default");
        assertThat(ConversationKeys.resolve("")).isEqualTo("chat-default");
        assertThat(ConversationKeys.resolve("  ")).isEqualTo("chat-default");
        assertThat(ConversationKeys.resolve("mem-1")).isEqualTo("chat-mem-1");
        assertThat(ConversationKeys.resolve("  abc  ")).isEqualTo("chat-abc");
    }
}
