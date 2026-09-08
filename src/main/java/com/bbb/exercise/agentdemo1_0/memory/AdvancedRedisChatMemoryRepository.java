package com.bbb.exercise.agentdemo1_0.memory;

import com.bbb.exercise.agentdemo1_0.dto.MessageWithConversation;
import com.bbb.exercise.agentdemo1_0.dto.PageResult;
import org.springframework.ai.chat.memory.ChatMemoryRepository;

import java.time.Instant;
import java.util.List;

/**
 * 高级 Redis 会话记忆查询接口。
 *
 * <p>方法签名<b>对齐 Spring AI 2.0+ 官方 {@code AdvancedRedisChatMemoryRepository}</b>，
 * 以便未来项目升级到 Spring AI 2.0 + 官方 Redis Stack 实现时，业务层调用方零改动平滑迁移。
 *
 * <p>继承 {@link ChatMemoryRepository} 基础读写能力（{@code findConversationIds /
 * findByConversationId / saveAll / deleteByConversationId}），扩展按类型 / 内容 / 时间范围 / 元数据的查询，
 * 以及本项目所需的<b>真分页</b>（官方仅有 limit 无 offset，分页为本接口自行扩展）。
 *
 * <p><b>实现说明</b>：本项目保持 Spring AI 1.0.5 基线，官方 {@code RedisChatMemoryRepository} /
 * {@code AdvancedRedisChatMemoryRepository} 仅在 2.0+ 且需 Redis Stack（含 RedisJSON + RediSearch）。
 * 本接口由 {@link RedisChatMemoryRepository} 用<b>普通 Redis</b>（StringRedisTemplate + Sorted Set）实现，
 * 高级查询采用全量遍历 + 内存过滤（演示可接受，生产建议升级 Redis Stack 获得原生搜索能力）。
 *
 * @see RedisChatMemoryRepository
 */
public interface AdvancedRedisChatMemoryRepository extends ChatMemoryRepository {

    /**
     * 按消息类型查询（跨所有会话）。
     *
     * @param messageType 类型字符串（{@link MessageWithConversation#TYPE_USER} 等）
     * @param limit        最多返回条数
     * @return 匹配的消息列表（按时间升序）
     */
    List<MessageWithConversation> findByType(String messageType, int limit);

    /**
     * 按内容关键词查询（跨所有会话，包含匹配，大小写不敏感）。
     *
     * @param content 关键词
     * @param limit   最多返回条数
     */
    List<MessageWithConversation> findByContent(String content, int limit);

    /**
     * 按时间范围查询某会话内的消息（时间闭区间，升序）。
     *
     * @param conversationId 会话 ID
     * @param from           起始时间（含），可为 {@code null} 表示不设下界
     * @param to             结束时间（含），可为 {@code null} 表示不设上界
     * @param limit          最多返回条数
     */
    List<MessageWithConversation> findByTimeRange(String conversationId, Instant from, Instant to, int limit);

    /**
     * 按元数据键值查询（跨所有会话）。
     *
     * @param key   元数据键
     * @param value 元数据值（字符串相等比较）
     * @param limit 最多返回条数
     */
    List<MessageWithConversation> findByMetadata(String key, String value, int limit);

    /**
     * 执行自定义查询。
     *
     * <p>本项目普通 Redis 不支持 RediSearch 完整语法，仅做<b>降级实现</b>：
     * 解析 {@code @type:XXX} 与 {@code @content:YYY} 片段进行组合过滤。
     * 如需完整 RediSearch 语法，请升级到 Redis Stack。
     *
     * @param query 查询字符串（如 {@code "@type:USER @content:北京"}）
     * @param limit 最多返回条数
     */
    List<MessageWithConversation> executeQuery(String query, int limit);

    /**
     * 真分页查询某会话的消息（按时间升序）。
     *
     * <p>官方 AdvancedRedisChatMemoryRepository 仅支持 limit 不支持 offset，
     * 本方法为满足「会话历史分页查询」需求自行扩展，基于 Sorted Set 的 ZRANGE offset+count 实现。
     *
     * @param conversationId 会话 ID
     * @param page           页码（从 1 开始）
     * @param size           每页条数
     * @return 分页结果
     */
    PageResult<MessageWithConversation> pageByConversation(String conversationId, int page, int size);

    /**
     * 统计某会话的消息总数。
     *
     * @param conversationId 会话 ID
     * @return 消息条数
     */
    long countByConversation(String conversationId);
}
