package com.bbb.exercise.agentdemo1_0.controller;

import com.bbb.exercise.agentdemo1_0.dto.ChatRequest;
import com.bbb.exercise.agentdemo1_0.service.ChatService;
import com.bbb.exercise.agentdemo1_0.support.ConversationKeys;
import com.bbb.exercise.agentdemo1_0.vo.ChatEventVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
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
 * 聊天相关 API 控制器（支持流式/同步、历史管理、中断生成）
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
     * 查询会话历史消息列表
     */
    @GetMapping("/history")
    public Map<String, Object> history(@RequestParam(value = "sessionId", required = false) String sessionId) {
        String conversationId = ConversationKeys.resolve(sessionId);
        List<String> history = chatService.history(sessionId);
        log.info("[history] 查询历史 sessionId={} size={}", conversationId, history.size());
        return Map.of("history", history, "count", history.size());
    }

    /**
     * 清空指定会话的历史记录
     */
    @DeleteMapping("/history")
    public Map<String, Object> clear(@RequestParam(value = "sessionId", required = false) String sessionId) {
        String conversationId = ConversationKeys.resolve(sessionId);
        log.info("[clear] 清空历史 sessionId={}", conversationId);
        chatService.clear(sessionId);
        return Map.of("cleared", true);
    }
}