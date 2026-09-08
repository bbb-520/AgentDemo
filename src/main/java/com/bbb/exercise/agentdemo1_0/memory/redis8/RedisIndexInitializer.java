package com.bbb.exercise.agentdemo1_0.memory.redis8;

import com.bbb.exercise.agentdemo1_0.config.ChatMemoryProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * RediSearch 索引初始化器（{@code @PostConstruct} 启动期建索引）。
 *
 * <p><b>schema</b>（作用于 RedisJSON 文档）：
 * <pre>
 *   FT.CREATE chat-memory-idx ON JSON
 *     PREFIX 1 "chat:memory:"
 *     SCHEMA
 *       $.conversationId AS conversationId TAG
 *       $.messageType    AS messageType    TAG
 *       $.content        AS content        TEXT
 *       $.seq            AS seq            NUMERIC SORTABLE
 *       $.timestamp      AS timestamp      TAG SORTABLE
 *       $.metadata.summary AS summary      TAG
 * </pre>
 *
 * <p>字段类型选择：
 * <ul>
 *     <li>{@code conversationId} / {@code messageType}：TAG —— 精确匹配，避免 TEXT 分词把 {@code chat-default}
 *         拆成 {@code chat} + {@code default}；</li>
 *     <li>{@code content}：TEXT —— 全文搜索（默认分词器对中文按字切分，够用）；
 *         如需更好中文召回可考虑指定 {@code LANGUAGE chinese}；</li>
 *     <li>{@code seq}：NUMERIC SORTABLE —— 替代旧 Sorted Set 的 score，用于真分页 {@code SORTBY seq ASC}；</li>
 *     <li>{@code timestamp}：TAG SORTABLE —— 时间范围查询 + 排序（ISO-8601 字符串字典序与时间序一致）；</li>
 *     <li>{@code metadata.summary}：TAG —— 让 {@code findByMetadata("summary","true")} 走索引。</li>
 * </ul>
 *
 * <p><b>幂等</b>：索引已存在则跳过；schema 变更需手工调 {@link #recreate()} 重建（Redis 8 的
 * {@code FT.ALTER} 支持加字段，但本项目暂未用）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisIndexInitializer {

    private final RedisJsonCommands commands;
    private final ChatMemoryProperties props;

    @PostConstruct
    public void init() {
        if (!props.getRedis().isInitializeSchema()) {
            log.info("[redis8] 索引自动初始化已关闭（chat.memory.redis.initialize-schema=false）");
            return;
        }
        String indexName = props.getRedis().getIndexName();
        try {
            if (commands.ftIndexExists(indexName)) {
                log.info("[redis8] 索引已存在，跳过创建 index={}", indexName);
                return;
            }
            List<String> args = buildIndexArgs(indexName, props.getRedis().getKeyPrefix());
            Object resp = commands.ftCreate(args);
            log.info("[redis8] 索引已创建 index={} resp={}", indexName, resp);
        } catch (Exception e) {
            // 索引创建失败不应阻断应用启动，但日志必须清晰
            log.error("[redis8] 索引创建失败 index={} err={}",
                    props.getRedis().getIndexName(), e.toString());
        }
    }

    /**
     * 重建索引（删除现有并重建；不删数据）。
     *
     * <p><b>注意</b>：重建期间仍有写入的话，旧字段仍可查但新字段不生效；
     * 建议在低峰期调用，或先停写。
     */
    public void recreate() {
        String indexName = props.getRedis().getIndexName();
        try {
            if (commands.ftIndexExists(indexName)) {
                log.warn("[redis8] 重建索引：先删除旧索引 index={}", indexName);
                commands.ftDropIndex(indexName);
            }
            List<String> args = buildIndexArgs(indexName, props.getRedis().getKeyPrefix());
            commands.ftCreate(args);
            log.info("[redis8] 索引重建完成 index={}", indexName);
        } catch (Exception e) {
            log.error("[redis8] 索引重建失败 index={} err={}", indexName, e.toString());
        }
    }

    /** 列出当前 Redis 上所有索引（运维辅助）。 */
    public List<String> listIndexes() {
        List<String> out = new ArrayList<>();
        for (Object o : commands.ftList()) {
            out.add(o.toString());
        }
        return out;
    }

    static List<String> buildIndexArgs(String indexName, String keyPrefix) {
        List<String> args = new ArrayList<>();
        args.add(indexName);
        args.add("ON");
        args.add("JSON");
        args.add("PREFIX");
        args.add("1");
        args.add(keyPrefix);
        args.add("SCHEMA");
        args.add("$.conversationId");
        args.add("AS");
        args.add("conversationId");
        args.add("TAG");
        args.add("$.messageType");
        args.add("AS");
        args.add("messageType");
        args.add("TAG");
        args.add("$.content");
        args.add("AS");
        args.add("content");
        args.add("TEXT");
        args.add("$.seq");
        args.add("AS");
        args.add("seq");
        args.add("NUMERIC");
        args.add("SORTABLE");
        args.add("$.timestamp");
        args.add("AS");
        args.add("timestamp");
        args.add("TAG");
        args.add("SORTABLE");
        args.add("$.metadata.summary");
        args.add("AS");
        args.add("summary");
        args.add("TAG");
        return args;
    }
}
