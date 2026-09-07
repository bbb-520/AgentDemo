package com.bbb.exercise.agentdemo1_0.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 对话请求参数。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatRequest {

    /** 用户的问题（必填） */
    private String question;

    /**
     * 会话 id，用于串联多轮对话记忆。
     * 不传时使用默认会话（同一进程内共享）。
     */
    private String sessionId;
}
