package com.bbb.exercise.agentdemo1_0.controller;

import com.bbb.exercise.agentdemo1_0.dto.ChatRequest;
import com.bbb.exercise.agentdemo1_0.dto.MessageWithConversation;
import com.bbb.exercise.agentdemo1_0.dto.PageResult;
import com.bbb.exercise.agentdemo1_0.service.ChatService;
import com.bbb.exercise.agentdemo1_0.utils.ConversationKeys;
import com.bbb.exercise.agentdemo1_0.vo.ChatEventVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import com.bbb.exercise.agentdemo1_0.utils.StringUtils;

/**
 * 聊天相关 API 控制器（支持流式/同步、历史管理、中断生成、二级记忆查询与压缩）。
 */
@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    /**
     * 流式聊天（SSE 事件流）
     */
    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ChatEventVO> chat(@RequestBody ChatRequest request) {
        String conversationId = ConversationKeys.resolve(request.getSessionId());
        log.info("[chat] 收到流式请求 sessionId={} question={}", conversationId, request.getQuestion());
        return chatService.chat(request.getQuestion(), request.getSessionId());
    }

    /**
     * 同步聊天（一次性返回完整结果）
     */
    @PostMapping(value = "/sync", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> chatSync(@RequestBody ChatRequest request) {
        String conversationId = ConversationKeys.resolve(request.getSessionId());
        log.info("[chat-sync] 收到同步请求 sessionId={} question={}", conversationId, request.getQuestion());
        String answer = chatService.chatSync(request.getQuestion(), request.getSessionId());
        return Map.of("answer", StringUtils.toString(answer));
    }

    /**
     * 中断当前会话的生成
     */
    @PostMapping("/stop")
    public Map<String, Object> stop(@RequestParam("sessionId") String sessionId) {
        String conversationId = ConversationKeys.resolve(sessionId);
        log.info("[stop] 停止生成 sessionId={}", conversationId);
        chatService.stop(sessionId);
        return Map.of("stopped", true, "sessionId", StringUtils.trim(sessionId));
    }

    /**
     * 查询会话历史消息列表（一级内存窗口，格式化为「我:/助手:」）。
     */
    @GetMapping("/history")
    public Map<String, Object> history(@RequestParam(value = "sessionId", required = false) String sessionId) {
        String conversationId = ConversationKeys.resolve(sessionId);
        List<String> history = chatService.history(sessionId);
        log.info("[history] 查询历史 sessionId={} size={}", conversationId, history.size());
        return Map.of("history", history, "count", history.size());
    }

    /**
     * 清空指定会话的历史记录（双清：内存窗口 + Redis）。
     */
    @DeleteMapping("/history")
    public Map<String, Object> clear(@RequestParam(value = "sessionId", required = false) String sessionId) {
        String conversationId = ConversationKeys.resolve(sessionId);
        log.info("[clear] 清空历史 sessionId={}", conversationId);
        chatService.clear(sessionId);
        return Map.of("cleared", true);
    }

    /**
     * 分页查询会话历史（Redis 二级存储，按时间升序）。
     * <p>对应需求 1.3.1 会话历史分页查询。
     *
     * @param sessionId 会话 id
     * @param page      页码（从 1 开始，默认 1）
     * @param size      每页条数（默认 20）
     * @return 分页结果，每条含会话 ID、消息类型、内容、元数据、时间戳
     */
    @GetMapping("/{sessionId}/messages")
    public PageResult<MessageWithConversation> pageHistory(
            @PathVariable String sessionId,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        String conversationId = ConversationKeys.resolve(sessionId);
        PageResult<MessageWithConversation> result = chatService.pageHistory(sessionId, page, size);
        log.info("[history-page] sessionId={} page={} size={} total={}",
                conversationId, page, size, result.getTotal());
        return result;
    }

    /**
     * 按会话 ID 获取全部消息（Redis 二级存储，含摘要）。
     * <p>对应需求 1.3.2 按会话 ID 获取全部消息。
     *
     * @param sessionId 会话 id
     * @return 全部消息（按时间升序）
     */
    @GetMapping("/{sessionId}/messages/all")
    public List<MessageWithConversation> messagesAll(@PathVariable String sessionId) {
        String conversationId = ConversationKeys.resolve(sessionId);
        List<MessageWithConversation> msgs = chatService.messagesByConversation(sessionId);
        log.info("[history-all] sessionId={} count={}", conversationId, msgs.size());
        return msgs;
    }

    /**
     * 手动触发会话压缩（生成摘要并双写）。
     * <p>对应需求 1.3.3 对会话历史二次提问（压缩概括）。
     *
     * @param sessionId 会话 id
     * @return {@code summarized=true} 表示已生成摘要，{@code summary} 为摘要文本；无需压缩时 {@code summarized=false}
     */
    @PostMapping("/{sessionId}/summarize")
    public Map<String, Object> summarize(@PathVariable String sessionId) {
        String conversationId = ConversationKeys.resolve(sessionId);
        log.info("[summarize] 手动触发压缩 sessionId={}", conversationId);
        String summary = chatService.summarize(sessionId);
        boolean done = summary != null;
        return Map.of(
                "summarized", done,
                "sessionId", sessionId,
                "summary", done ? summary : ""
        );
    }
}
