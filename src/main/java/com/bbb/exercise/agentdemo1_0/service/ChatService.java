package com.bbb.exercise.agentdemo1_0.service;

import com.bbb.exercise.agentdemo1_0.dto.MessageWithConversation;
import com.bbb.exercise.agentdemo1_0.dto.PageResult;
import com.bbb.exercise.agentdemo1_0.vo.ChatEventVO;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 对话服务：对外提供流式与非流式两种对话能力，以及会话历史管理（二级记忆）。
 */
public interface ChatService {

    /**
     * 流式对话
     *
     * @param question  用户问题
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
     * 查询会话历史（一级内存窗口，格式化为「我:/助手:」）。
     */
    List<String> history(String sessionId);

    /**
     * 清空会话历史（双清：内存窗口 + Redis）。
     */
    void clear(String sessionId);

    /**
     * 分页查询会话历史（从 Redis 二级存储，按时间升序）。
     *
     * @param sessionId 会话 id（前端传入，内部解析为 conversationId）
     * @param page      页码（从 1 开始）
     * @param size      每页条数
     * @return 分页结果，每条携带会话 ID、消息类型、内容、元数据、时间戳
     */
    PageResult<MessageWithConversation> pageHistory(String sessionId, int page, int size);

    /**
     * 按会话 ID 获取全部消息（从 Redis 二级存储，含已生成的摘要消息）。
     *
     * @param sessionId 会话 id
     * @return 全部消息（按时间升序，受 {@code chat.memory.redis.max-messages-per-conversation} 上限保护）
     */
    List<MessageWithConversation> messagesByConversation(String sessionId);

    /**
     * 手动触发会话压缩（生成摘要并双写）。
     *
     * @param sessionId 会话 id
     * @return 生成的摘要文本；若消息太少或新积累不足而跳过，返回 {@code null}
     */
    String summarize(String sessionId);
}
