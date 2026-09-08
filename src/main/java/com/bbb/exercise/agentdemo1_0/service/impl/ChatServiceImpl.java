package com.bbb.exercise.agentdemo1_0.service.impl;

import com.bbb.exercise.agentdemo1_0.agent.AgentRunner;
import com.bbb.exercise.agentdemo1_0.config.ChatMemoryProperties;
import com.bbb.exercise.agentdemo1_0.dto.MessageWithConversation;
import com.bbb.exercise.agentdemo1_0.dto.PageResult;
import com.bbb.exercise.agentdemo1_0.enums.ChatEventTypeEnum;
import com.bbb.exercise.agentdemo1_0.memory.MemoryCompressionService;
import com.bbb.exercise.agentdemo1_0.memory.RedisChatMemoryRepository;
import com.bbb.exercise.agentdemo1_0.service.ChatService;
import com.bbb.exercise.agentdemo1_0.utils.ConversationKeys;
import com.bbb.exercise.agentdemo1_0.vo.ChatEventVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.bbb.exercise.agentdemo1_0.utils.StringUtils;

import static com.bbb.exercise.agentdemo1_0.enums.ChatEventTypeEnum.DATA;

/**
 * 聊天服务实现，提供流式对话、同步对话、停止、历史记录和清理功能。
 *
 * <p>新增二级记忆能力：分页历史 / 全量消息 / 手动压缩，委托
 * {@link RedisChatMemoryRepository} 与 {@link MemoryCompressionService}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {

    private final AgentRunner agentRunner;

    /** Redis 二级长期会话历史（分页 / 全量查询） */
    private final RedisChatMemoryRepository redisRepo;

    /** 会话压缩服务（手动触发压缩） */
    private final MemoryCompressionService compressionService;

    /** 会话记忆配置（取 Redis 全量查询的上限） */
    private final ChatMemoryProperties chatMemoryProperties;

    /** 标记每个会话是否正在生成回复 */
    private static final Map<String, Boolean> GENERATING = new ConcurrentHashMap<>();

    /** 流结束事件，固定复用 */
    private static final ChatEventVO STOP_EVENT =
            ChatEventVO.builder().eventType(ChatEventTypeEnum.STOP.getValue()).build();

    /**
     * 流式聊天：将问题交给 AgentRunner，把返回的直播事件流原样透传给前端。
     * 出错时把错误替换为 ERROR(1004) 事件（保持既有契约）。
     *
     * @param question  用户问题
     * @param sessionId 会话ID
     * @return 直播事件流
     */
    @Override
    public Flux<ChatEventVO> chat(String question, String sessionId) {
        if (StringUtils.isBlank(question)) {
            return Flux.just(errorEvent("问题不能为空"));
        }

        String conversationId = ConversationKeys.resolve(sessionId);
        StringBuilder output = new StringBuilder();

        return agentRunner.stream(question, conversationId)
                // 开始生成前设置标记
                .doFirst(() -> GENERATING.put(conversationId, Boolean.TRUE))
                // 正常完成时清除标记
                .doOnComplete(() -> GENERATING.remove(conversationId))
                // 出错时清除标记
                .doOnError(err -> GENERATING.remove(conversationId))
                // 取消时保存已生成的部分回复（SSE 已断开/被 /stop 截断，无法再推送事件，
                // 此处按既有契约回写记忆；用量统计 USAGE 已由 AgentRunner 在正常结束时推送）
                .doOnCancel(() -> {
                    GENERATING.remove(conversationId);
                    agentRunner.savePartialResponse(conversationId, output.toString());
                })
                // 只要标记为true就继续接收，否则停止（stop() 把标记移除后流即被截断）
                .takeWhile(event -> Boolean.TRUE.equals(GENERATING.get(conversationId)))
                // 仅累计 DATA 文本（最终回答），用于中断时保存部分回答
                .doOnNext(event -> {
                    if (event.getEventType() == DATA.getValue()
                            && event.getEventData() instanceof String text && StringUtils.isNotEmpty(text)) {
                        output.append(text);
                    }
                })
                // 末尾追加停止事件
                .concatWith(Flux.just(STOP_EVENT))
                // 出错时把剩余流替换为一条错误事件
                .onErrorResume(err -> {
                    log.error("[agent] 失败 sessionId={} err={}", conversationId, err.toString());
                    return Flux.just(errorEvent("对话失败: " + err.getMessage()));
                });
    }

    /**
     * 同步聊天：阻塞等待完整回复。
     */
    @Override
    public String chatSync(String question, String sessionId) {
        if (StringUtils.isBlank(question)) {
            return "问题不能为空";
        }
        return agentRunner.chat(question, ConversationKeys.resolve(sessionId));
    }

    /**
     * 停止指定会话的流式生成。
     */
    @Override
    public void stop(String sessionId) {
        GENERATING.remove(ConversationKeys.resolve(sessionId));
    }

    /**
     * 获取会话历史消息列表（一级内存窗口），格式化为可读字符串。
     */
    @Override
    public List<String> history(String sessionId) {
        List<Message> messages = agentRunner.history(ConversationKeys.resolve(sessionId));
        List<String> result = new ArrayList<>(messages.size());
        for (Message message : messages) {
            String text = message.getText();
            if (StringUtils.isBlank(text)) {
                continue;
            }
            if (message instanceof UserMessage) {
                result.add("我: " + text);
            } else if (message instanceof AssistantMessage assistant && !assistant.hasToolCalls()) {
                result.add("助手: " + text);
            }
        }
        return result;
    }

    /**
     * 清除指定会话的历史记录（双清：内存窗口 + Redis）。
     */
    @Override
    public void clear(String sessionId) {
        agentRunner.clearHistory(ConversationKeys.resolve(sessionId));
    }

    /**
     * 分页查询会话历史（Redis 二级存储，按时间升序）。
     */
    @Override
    public PageResult<MessageWithConversation> pageHistory(String sessionId, int page, int size) {
        if (page < 1) {
            page = 1;
        }
        if (size < 1) {
            size = 20;
        }
        return redisRepo.pageByConversation(ConversationKeys.resolve(sessionId), page, size);
    }

    /**
     * 按会话 ID 获取全部消息（Redis 二级存储，含摘要）。
     */
    @Override
    public List<MessageWithConversation> messagesByConversation(String sessionId) {
        int max = chatMemoryProperties.getRedis().getMaxMessagesPerConversation();
        return redisRepo.pageByConversation(ConversationKeys.resolve(sessionId), 1, max).getRecords();
    }

    /**
     * 手动触发会话压缩（生成摘要并双写 Redis 与内存窗口）。
     */
    @Override
    public String summarize(String sessionId) {
        return compressionService.summarize(ConversationKeys.resolve(sessionId));
    }

    /**
     * 构造错误事件。
     */
    private static ChatEventVO errorEvent(String message) {
        return ChatEventVO.builder()
                .eventType(ChatEventTypeEnum.ERROR.getValue())
                .eventData(message)
                .build();
    }
}
