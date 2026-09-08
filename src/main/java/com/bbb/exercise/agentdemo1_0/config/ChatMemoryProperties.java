package com.bbb.exercise.agentdemo1_0.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 会话记忆配置属性（绑定 {@code application.yml} 的 {@code chat.memory.*} 段）。
 *
 * <p>支撑<b>二级记忆方案</b>（Redis 8 改造后）：
 * <ul>
 *   <li>一级（短时）：{@link #windowSize} 条 LRU 内存窗口 + {@link #maxConversations} 个会话上限，
 *       全部由 {@code AiConfiguration} 用自建 LRU 仓库保证生效；</li>
 *   <li>二级（长时）：RedisJSON 文档（每条消息 1 个）+ RediSearch 内置索引
 *       （{@link Redis#getIndexName()}），高级查询走 {@code FT.SEARCH}；</li>
 *   <li>压缩：{@link #summary} 段控制 LLM 摘要调度；
 *   <li>Redis Streams：{@link Redis#getStreams()} 控制压缩任务持久化（重启不丢任务）。</li>
 * </ul>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "chat.memory")
public class ChatMemoryProperties {

    /** 一级内存窗口大小（供 LLM 实时上下文，超出从最早开始淘汰；SystemMessage 豁免） */
    private int windowSize = 50;

    /** 内存中最大驻留会话数（超出按 LRU 淘汰最久未访问者；0 表示不限制） */
    private int maxConversations = 1000;

    /** Redis 长期存储 */
    private Redis redis = new Redis();

    /** 压缩概括 */
    private Summary summary = new Summary();

    @Getter
    @Setter
    public static class Redis {

        /** Redis 键前缀：{@code chat:memory:<conversationId>:<seq>} */
        private String keyPrefix = "chat:memory:";

        /** Redis 过期时间（解决内存无限增长风险） */
        private Duration timeToLive = Duration.ofDays(7);

        /** 单会话返回消息数上限（防全量拉取爆内存） */
        private int maxMessagesPerConversation = 1000;

        // -------- Redis 8 改造新增字段 --------

        /** RediSearch 索引名（{@code FT.CREATE} 的 idx） */
        private String indexName = "chat-memory-idx";

        /** 启动期自动建索引（关闭后由人工 FT.CREATE） */
        private boolean initializeSchema = true;

        /** 启动期自动清理旧 Sorted Set 结构（改造前演示残留），关闭后保留旧 key */
        private boolean cleanupLegacyOnStartup = true;

        /** Streams 段（压缩任务持久化） */
        private Streams streams = new Streams();

        @Getter
        @Setter
        public static class Streams {
            /** 总开关；关闭则压缩任务来源仍退回进程内 ScheduledExecutorService */
            private boolean enabled = true;

            /** Stream 名 */
            private String key = "chat:memory:compress-tasks";

            /** 消费者组（多个应用实例共享负载） */
            private String consumerGroup = "memory-compressors";

            /** 单实例消费者名（建议按 hostname 区分，便于横向扩展） */
            private String consumerName = "consumer-1";

            /** XREADGROUP 拉取间隔（毫秒）；同时也是 BLOCK 时间 */
            private long pollIntervalMs = 30_000L;

            /** 单次拉取最大条数 */
            private int batchSize = 20;
        }
    }

    @Getter
    @Setter
    public static class Summary {

        /** 压缩后保留最近 N 条原文 */
        private int keepRecent = 10;

        /** 最后一条消息后空闲多少分钟触发压缩 */
        private int idleMinutes = 10;

        /** 摘要最大字数（控制引用时 token 用量） */
        private int maxLength = 200;

        /** 压缩调度扫描间隔（秒）—— 仅当 streams.enabled=false 时生效 */
        private int scanIntervalSeconds = 60;

        /** 是否启用后台自动压缩 */
        private boolean enabled = true;
    }
}
