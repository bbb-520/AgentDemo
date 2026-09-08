package com.bbb.exercise.agentdemo1_0.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * 带会话标识的消息 DTO（对齐 Spring AI 2.0+ 官方 {@code AdvancedRedisChatMemoryRepository} 的返回类型，
 * 便于未来升级 2.0 时平滑迁移）。
 *
 * <p>携带消息所属会话 ID、类型、内容、元数据与时间戳。
 * 元数据 {@link #metadata} 常见键：
 * <ul>
 *     <li>{@code summary=true} —— 该条是压缩生成的摘要消息（由 {@code MemoryCompressionService} 写入）；</li>
 *     <li>{@code summarizedCount} —— 摘要覆盖的原始消息数；</li>
 *     <li>{@code rangeStart / rangeEnd} —— 摘要覆盖的时间范围。</li>
 * </ul>
 *
 * <p>用字符串 {@link #messageType} 而非 Spring AI 的 {@code MessageType} 枚举，
 * 是为了规避枚举在 1.0.5 → 2.0 间可能的签名变化，常量定义在本类。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MessageWithConversation {

    /** 消息类型常量（对应 Spring AI MessageType 枚举的字符串形式） */
    public static final String TYPE_USER = "USER";
    public static final String TYPE_ASSISTANT = "ASSISTANT";
    public static final String TYPE_SYSTEM = "SYSTEM";
    public static final String TYPE_TOOL = "TOOL";

    /** 元数据键：标识该消息为压缩摘要 */
    public static final String META_SUMMARY = "summary";
    /** 元数据键：摘要覆盖的原始消息数 */
    public static final String META_SUMMARIZED_COUNT = "summarizedCount";
    /** 元数据键：摘要覆盖时间范围起点 */
    public static final String META_RANGE_START = "rangeStart";
    /** 元数据键：摘要覆盖时间范围终点 */
    public static final String META_RANGE_END = "rangeEnd";

    /** 所属会话 ID（内部键，形如 {@code chat-xxx}） */
    private String conversationId;

    /** 消息类型（USER / ASSISTANT / SYSTEM / TOOL） */
    private String messageType;

    /** 消息文本内容 */
    private String content;

    /** 元数据 */
    private Map<String, Object> metadata;

    /** 消息时间戳 */
    private Instant timestamp;

    /** 当前消息在所属会话里的自增序号（Redis 8 改造后写入 JSON 文档；旧版不存在为 null） */
    private Long seq;

    /** 是否为压缩摘要消息 */
    public boolean isSummary() {
        return metadata != null && Boolean.TRUE.equals(metadata.get(META_SUMMARY));
    }
}
