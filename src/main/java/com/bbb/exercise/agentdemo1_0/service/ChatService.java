package com.bbb.exercise.agentdemo1_0.service;

import com.bbb.exercise.agentdemo1_0.vo.ChatEventVO;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 对话服务：对外提供流式与非流式两种对话能力。
 */
public interface ChatService {

    /**
     * 流式对话
     *
     * @param question 用户问题
     * @param sessionId 会话 id（为空则用默认会话）
     * @return 事件流，每个元素为一个 {@link ChatEventVO}
     */
    Flux<ChatEventVO> chat(String question, String sessionId);

    /**
     * 同步对话：一次性返回完整答案
     */
    String chatSync(String question, String sessionId);

    /**
     * 停止生成
     *
     * @param sessionId 会话 id
     */
    void stop(String sessionId);

    /**
     * 查询会话历史
     */
    List<String> history(String sessionId);

    /**
     * 清空会话历史
     */
    void clear(String sessionId);
}
