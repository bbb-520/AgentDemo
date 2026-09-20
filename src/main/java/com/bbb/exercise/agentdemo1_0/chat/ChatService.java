package com.bbb.exercise.agentdemo1_0.chat;

import com.bbb.exercise.agentdemo1_0.enums.ChatEventTypeEnum;
import com.bbb.exercise.agentdemo1_0.conversation.ConversationPersistenceService;
import com.bbb.exercise.agentdemo1_0.conversation.ConversationSession;
import com.bbb.exercise.agentdemo1_0.identity.ChatIdentity;
import com.bbb.exercise.agentdemo1_0.utils.ConversationKeys;
import com.bbb.exercise.agentdemo1_0.utils.StringUtils;
import com.bbb.exercise.agentdemo1_0.vo.ChatEventVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.Map;


/**
 * 对话核心服务：负责参数校验、调用模型、编排事件流。
 *
 * <p>一次流式对话的事件顺序（对外契约）：<br>
 * {@code SESSION_INFO} → {@code DATA} × N → {@code STOP}
 *
 * <p>短期上下文（Redis 持久化 + 内存滑窗）由 {@code MessageChatMemoryAdvisor} 自动读写；
 * 会话归属和完整消息由 MyBatis-Plus 写入 MySQL；
 * 工具调用由 Spring AI 的 {@code ToolCallingAdvisor} 自动完成「调用工具 → 回传结果 → 再问模型」的循环，
 * 调用过程不对外直播，模型只把最终答案以 {@code DATA} 事件输出。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    /** 单次提问的最大字符数（防御性上限：避免超长输入直接进模型带来的成本与滥用风险） */
    private static final int MAX_QUESTION_LENGTH = 4000;

    private final ChatClient chatClient;
    private final ConversationPersistenceService conversationPersistenceService;


    /**
     * 在开始 SSE 之前创建新会话或校验已有会话的归属。
     * 数据库操作放到 boundedElastic，避免阻塞 WebFlux 事件循环。
     */
    public Mono<ConversationSession> openConversation(String sessionId, ChatIdentity identity) {
        return Mono.fromCallable(() -> conversationPersistenceService.openOrCreate(sessionId, identity))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 流式对话：把模型的正文增量转成 {@code DATA} 事件持续推送，并保存长期消息。
     *
     * <p>流程：校验参数 → 发起模型流式调用 → 首发会话信息 → 逐条推送正文 → 追发结束事件。
     *
     * @param question  用户问题
     * @param session 已完成身份校验的会话
     * @return 事件流；参数非法或异常时返回「错误事件 + 结束事件」而非抛异常，保证 HTTP 层始终返回 200 的 SSE
     */
    public Flux<ChatEventVO> chat(String question, ConversationSession session) {
        //判断问题是否为空
        String invalid = validateQuestion(question);
        if (invalid != null) {
            return Flux.just(errorEvent(invalid), stopEvent());
        }
        String conversationId = session.conversationId();
        String memoryKey = ConversationKeys.memoryKey(session.identity(), conversationId);
        StringBuilder assistantText = new StringBuilder();

        // 模型正文流：从 ChatClient 拿到增量响应 → 取文本 → 转成 DATA 事件
        // 会话键绑定 tenant:user:conversation，不能只使用客户端传入的 conversationId。
        Flux<ChatEventVO> contentEvents = Flux.defer(() -> chatClient.prompt()
                        .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, memoryKey))
                        .user(question)
                        .stream()
                        .chatResponse()
                        .mapNotNull(ChatService::textOf)
                        .filter(StringUtils::isNotEmpty)
                        .doOnNext(assistantText::append)
                        .map(ChatService::dataEvent));

        Mono<Void> saveUserMessage = Mono.<Void>fromRunnable(() ->
                        conversationPersistenceService.appendUserMessage(session, question))
                .subscribeOn(Schedulers.boundedElastic());
        Mono<Void> saveAssistantMessage = Mono.<Void>fromRunnable(() ->
                        conversationPersistenceService.appendAssistantMessage(session, assistantText.toString(), true))
                .subscribeOn(Schedulers.boundedElastic());

        return Flux.concat(
                        // 首发会话信息（便于前端拿到 conversationId）
                        Flux.just(sessionInfoEvent(conversationId, session.created())),
                        // 正文增量
                        saveUserMessage.thenMany(contentEvents),
                        // 先落库，再发送 STOP，确保客户端收到结束事件时消息已写入 MySQL
                        saveAssistantMessage.thenMany(Flux.just(stopEvent())))
                .onErrorResume(err -> {
                    log.error("[chat] 对话失败 cid={} err={}", conversationId, err.toString());
                    return Mono.<Void>fromRunnable(() ->
                                    conversationPersistenceService.appendAssistantMessage(session, assistantText.toString(), false))
                            .subscribeOn(Schedulers.boundedElastic())
                            .onErrorResume(persistError -> {
                                log.error("[chat] 保存失败 cid={} err={}", conversationId, persistError.toString());
                                return Mono.empty();
                            })
                            .thenMany(Flux.just(errorEvent("对话失败: " + errorMessage(err)), stopEvent()));
                });
    }


    /** 校验问题：空 → 提示必填；超长 → 提示上限。合法返回 {@code null}。 */
    public static String validateQuestion(String question) {
        if (StringUtils.isBlank(question)) {
            return "问题不能为空";
        }
        if (question.length() > MAX_QUESTION_LENGTH) {
            return "问题过长（上限 " + MAX_QUESTION_LENGTH + " 字符）";
        }
        return null;
    }

    /** 取异常可读信息：优先 message，为空则退回异常类名。 */
    private static String errorMessage(Throwable err) {
        String message = err == null ? null : err.getMessage();
        return StringUtils.isBlank(message) ? StringUtils.toString(err) : message;
    }

    /** 从响应中取出模型输出的文本增量（无结果/无输出时返回 {@code null}）。 */
    private static String textOf(ChatResponse response) {
        if (response == null) {
            return null;
        }
        Generation generation = response.getResult();
        if (generation == null || generation.getOutput() == null) {
            return null;
        }
        return generation.getOutput().getText();
    }

    /** 构造正文增量事件。 */
    private static ChatEventVO dataEvent(String text) {
        return ChatEventVO.builder()
                .eventType(ChatEventTypeEnum.DATA.getValue())
                .eventData(text)
                .build();
    }

    /** 构造结束事件（流末尾必发，无数据）。 */
    private static ChatEventVO stopEvent() {
        return ChatEventVO.builder().eventType(ChatEventTypeEnum.STOP.getValue()).build();
    }

    /** 构造错误事件（仍以 200 的 SSE 通道下发，由前端按 eventType 处理）。 */
    private static ChatEventVO errorEvent(String message) {
        return ChatEventVO.builder()
                .eventType(ChatEventTypeEnum.ERROR.getValue())
                .eventData(message)
                .build();
    }

    /** 构造会话信息事件：下发给前端会话 id 与起始时间戳。 */
    private static ChatEventVO sessionInfoEvent(String conversationId, boolean created) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("conversationId", conversationId);
        data.put("created", created);
        data.put("timestamp", System.currentTimeMillis());
        return ChatEventVO.builder()
                .eventType(ChatEventTypeEnum.SESSION_INFO.getValue())
                .eventData(data)
                .build();
    }
}
