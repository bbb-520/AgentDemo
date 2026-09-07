package com.bbb.exercise.agentdemo1_0.support;

import com.bbb.exercise.agentdemo1_0.utils.StringUtils;


public final class ConversationKeys {

    /** 会话键前缀 */
    public static final String PREFIX = "chat-";

    /** 默认会话键（前端未传 sessionId 时使用） */
    public static final String DEFAULT_ID = PREFIX + "default";

    private ConversationKeys() {
    }

    /**
     * 解析会话键。
     * @param sessionId 前端传入的会话 id，可为 {@code null} 或空白
     * @return 内部 conversationId
     */
    public static String resolve(String sessionId) {
        if (StringUtils.isBlank(sessionId)) {
            return DEFAULT_ID;
        }
        return PREFIX + StringUtils.trim(sessionId);
    }
}
