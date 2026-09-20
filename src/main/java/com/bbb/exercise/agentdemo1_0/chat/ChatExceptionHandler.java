package com.bbb.exercise.agentdemo1_0.chat;

import com.bbb.exercise.agentdemo1_0.conversation.ConversationPersistenceService.ConversationRequestException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** 在 SSE 响应开始前把非法会话请求转换为明确的 HTTP 错误。 */
@RestControllerAdvice
public class ChatExceptionHandler {

    @ExceptionHandler(ConversationRequestException.class)
    public ResponseEntity<Map<String, Object>> handleConversationRequest(ConversationRequestException exception) {
        return ResponseEntity.status(exception.status())
                .body(Map.of("code", exception.status(), "message", exception.getMessage()));
    }
}
