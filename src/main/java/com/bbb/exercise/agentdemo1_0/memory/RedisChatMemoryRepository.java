package com.bbb.exercise.agentdemo1_0.memory;

import com.bbb.exercise.agentdemo1_0.config.ChatMemoryProperties;
import com.bbb.exercise.agentdemo1_0.dto.MessageWithConversation;
import com.bbb.exercise.agentdemo1_0.dto.PageResult;
import com.bbb.exercise.agentdemo1_0.memory.redis8.RedisJsonCommands;
import com.bbb.exercise.agentdemo1_0.memory.redis8.RedisJsonCommands.FtSearchResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Redis 8 二级会话记忆仓库（基于 RedisJSON + RediSearch/Query Engine 内置能力）。
 *
 * <h3>数据结构（Redis 8 内置类型）</h3>
 * <ul>
 *   <li><b>消息主体</b>：RedisJSON 文档，每个消息一个 key：
 *       <pre>chat:memory:&lt;conversationId&gt;:&lt;seq&gt;
 *       value = {
 *         "conversationId": "chat-default",
 *         "messageType": "USER",
 *         "content": "...",
 *         "seq": 17,
 *         "timestamp": "2026-09-08T05:12:33.412Z",
 *         "metadata": { "summary": true, ... }   // 可选
 *       }</pre>
 *       TTL 同 key-prefix 默认 7d。</li>
 *   <li><b>会话序号</b>：{@code chat:memory:seq:&lt;cid&gt;}，String，INCRBY 1 取号；</li>
 *   <li><b>活跃时间</b>：{@code chat:memory:act:&lt;cid&gt;}，String，epochMilli，供压缩调度判空闲。</li>
 *   <li><b>索引</b>：{@code chat-memory-idx ON JSON PREFIX 1 "chat:memory:"}，
 *       SCHEMA 含 conversationId/messageType/content/seq/timestamp/summary，
 *       由 {@code RedisIndexInitializer @PostConstruct} 建索引。</li>
 * </ul>
 *
 * <h3>较旧版的优化</h3>
 * <ol>
 *   <li><b>索引替代全量遍历</b>：{@link #findByType} / {@link #findByContent} / {@link #findByMetadata} /
 *       {@link #executeQuery} 全部走 {@code FT.SEARCH}，不再用 {@code KEYS} + 内存过滤；</li>
 *   <li><b>真分页</b>：{@code SORTBY seq ASC LIMIT offset count}，offset 仍然支持；</li>
 *   <li><b>无类型反序列化</b>：JSON 文档天然带字段，不需要 decode/encode member 拼接；</li>
 *   <li><b>查找会话列表</b>：{@code SCAN MATCH prefix* TYPE json}（非阻塞），取代 {@code KEYS}。</li>
 * </ol>
 *
 * <h3>向后兼容</h3>
 * <p>实现 {@link AdvancedRedisChatMemoryRepository}（{@link ChatMemoryRepository} 子接口），
 * 方法签名与改造前完全相同，调用方（{@code AgentRunner} / {@code ChatServiceImpl} /
 * {@code MemoryCompressionService}）无需改动。
 *
 * <h3>旧数据</h3>
 * <p>改造前是 {@code Sorted Set：chat:memory:&lt;cid&gt;} 的结构，每次启动
 * {@code DataMigrator} 会一次性 {@code SCAN + DEL} 清掉。迁移不兼容已说明，禁止跨版本混跑。
 */
@Slf4j
@Component
public class RedisChatMemoryRepository implements AdvancedRedisChatMemoryRepository {

    /** @type:USER 这种自定义语法 → RediSearch @messageType:{USER} */
    private static final Pattern QUERY_TYPE = Pattern.compile("@type:(\\S+)");
    private static final Pattern QUERY_CONTENT = Pattern.compile("@content:(\\S+)");
    /** @summary:true 这种自定义语法 → RediSearch @summary:{true} */
    private static final Pattern QUERY_SUMMARY = Pattern.compile("@summary:(\\S+)");
    /** @conversationId:chat-xxx 这种自定义语法 */
    private static final Pattern QUERY_CONV = Pattern.compile("@conversationId:(\\S+)");

    private final RedisJsonCommands commands;
    private final ObjectMapper json;
    private final ChatMemoryProperties props;

    public RedisChatMemoryRepository(RedisJsonCommands commands,
                                     @Qualifier("chatMemoryObjectMapper") ObjectMapper json,
                                     ChatMemoryProperties props) {
        this.commands = commands;
        this.json = json;
        this.props = props;
    }

    // =========================================================
    // ChatMemoryRepository 基础契约
    // =========================================================

    /**
     * 列出全部 conversationId。
     * <p>实现：{@code SCAN MATCH chat:memory:* TYPE json}（非阻塞）+ 跳过 seq/act 子键 + 解析 conversationId。
     * <p>解析要点：每个 key 形如 {@code chat:memory:chat-xxx:17}，conversationId 是第三个冒号段前缀。
     */
    @Override
    public List<String> findConversationIds() {
        java.util.LinkedHashMap<String, Boolean> ids = new java.util.LinkedHashMap<>();
        String prefix = props.getRedis().getKeyPrefix();
        String cursor = "0";
        do {
            List<Object> raw = commands.scanOnce(cursor, prefix + "*", 500, "json");
            if (raw.isEmpty()) {
                break;
            }
            cursor = raw.get(0) == null ? "0" : raw.get(0).toString();
            if (raw.size() < 2) continue;
            @SuppressWarnings("unchecked")
            List<Object> keys = (List<Object>) raw.get(1);
            for (Object k : keys) {
                if (k == null) continue;
                String key = k.toString();
                if (!key.startsWith(prefix)) continue;
                if (key.startsWith(prefix + "seq:") || key.startsWith(prefix + "act:")) continue;
                String cid = key.substring(prefix.length());
                int idx = cid.lastIndexOf(':');
                if (idx > 0) {
                    cid = cid.substring(0, idx);
                }
                ids.put(cid, Boolean.TRUE);
            }
        } while (!"0".equals(cursor));
        return new ArrayList<>(ids.keySet());
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        return findByConversationIdRaw(conversationId).stream()
                .map(this::toMessage)
                .toList();
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        deleteByConversationId(conversationId);
        appendMessages(conversationId, messages);
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        // ① 删该 cid 的全部 JSON 文档
        List<String> keys = searchKeys("@conversationId:{" + escapeTag(conversationId) + "}", Integer.MAX_VALUE);
        for (String key : keys) {
            commands.jsonDel(key);
        }
        // ② 删 seq/act 辅助键
        commands.del(seqKeyFor(conversationId));
        commands.del(actKeyFor(conversationId));
    }

    // =========================================================
    // 追加（双写）
    // =========================================================

    /**
     * 追加消息并刷新 TTL 与活跃时间。
     *
     * @param conversationId 会话 ID
     * @param messages       列表，写入顺序即 seq 顺序
     */
    public void appendMessages(String conversationId, List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        Duration ttl = props.getRedis().getTimeToLive();
        Instant now = Instant.now();
        for (Message msg : messages) {
            appendMessageInternal(conversationId, msg, null, now);
        }
        if (ttl != null && !ttl.isZero() && !ttl.isNegative()) {
            // seq key + act key 已经在内部设置 TTL，再统一刷一次确保
            commands.expire(seqKeyFor(conversationId), ttl);
            commands.expire(actKeyFor(conversationId), ttl);
        }
    }

    /** 单条追加语法糖 */
    public void appendMessage(String conversationId, Message message) {
        appendMessages(conversationId, List.of(message));
    }

    /** 追加带元数据的消息（压缩摘要入库用） */
    public void appendMessage(String conversationId, Message message, Map<String, Object> metadata) {
        Duration ttl = props.getRedis().getTimeToLive();
        Instant now = Instant.now();
        appendMessageInternal(conversationId, message, metadata, now);
        if (ttl != null && !ttl.isZero() && !ttl.isNegative()) {
            commands.expire(seqKeyFor(conversationId), ttl);
            commands.expire(actKeyFor(conversationId), ttl);
        }
    }

    private void appendMessageInternal(String conversationId, Message msg,
                                       Map<String, Object> metadata, Instant timestamp) {
        long seq = commands.incrBy(seqKeyFor(conversationId), 1L);
        MessageWithConversation mc = fromMessage(conversationId, msg, metadata, timestamp, seq);
        String key = keyFor(conversationId, seq);
        try {
            String jsonStr = json.writeValueAsString(mc);
            commands.jsonSet(key, jsonStr);
            Duration ttl = props.getRedis().getTimeToLive();
            if (ttl != null && !ttl.isZero() && !ttl.isNegative()) {
                commands.expire(key, ttl);
            }
        } catch (Exception e) {
            log.error("[memory] 写入 JSON 消息失败 cid={} seq={} err={}",
                    conversationId, seq, e.toString(), e);
        }
        // 活跃时间
        commands.set(actKeyFor(conversationId),
                String.valueOf(timestamp.toEpochMilli()),
                safeTtl());
    }

    private Duration safeTtl() {
        Duration ttl = props.getRedis().getTimeToLive();
        if (ttl == null || ttl.isZero() || ttl.isNegative()) return Duration.ofDays(7);
        return ttl;
    }

    // =========================================================
    // 高级查询（全部走 FT.SEARCH，无 KEYS / 无内存全量）
    // =========================================================

    @Override
    public List<MessageWithConversation> findByType(String messageType, int limit) {
        if (messageType == null || messageType.isBlank()) return Collections.emptyList();
        String dsl = "@messageType:{" + escapeTag(messageType.toUpperCase()) + "}";
        return searchAndHydrate(dsl, sortBySeqAsc(), limit, false);
    }

    @Override
    public List<MessageWithConversation> findByContent(String content, int limit) {
        if (content == null || content.isBlank()) return Collections.emptyList();
        // RediSearch 的 TEXT 字段用空格分词，但中文按字切分；这里用 OR 简化匹配
        String dsl = "@content:" + escapeText(content);
        return searchAndHydrate(dsl, sortBySeqAsc(), limit, false);
    }

    @Override
    public List<MessageWithConversation> findByTimeRange(String conversationId, Instant from, Instant to, int limit) {
        StringBuilder dsl = new StringBuilder();
        dsl.append("@conversationId:{").append(escapeTag(conversationId)).append("}");
        if (from != null || to != null) {
            dsl.append(" @timestamp:[")
                    .append(from == null ? "-inf" : from.toString())
                    .append(" ")
                    .append(to == null ? "+inf" : to.toString())
                    .append("]");
        }
        return searchAndHydrate(dsl.toString(), sortBySeqAsc(), limit, false);
    }

    @Override
    public List<MessageWithConversation> findByMetadata(String key, String value, int limit) {
        if (key == null) return Collections.emptyList();
        // 已知支持 summary；其它元数据键走 JSON.GET 后过滤。
        if (MessageWithConversation.META_SUMMARY.equals(key)) {
            String dsl = "@summary:{" + escapeTag(value == null ? "true" : value) + "}";
            return searchAndHydrate(dsl, sortBySeqAsc(), limit, false);
        }
        // 通用回退：先按 conversationId 全量（业务量不应大），内存过滤
        List<MessageWithConversation> all = new ArrayList<>();
        for (String cid : findConversationIds()) {
            all.addAll(findByConversationIdRaw(cid));
        }
        return all.stream()
                .filter(m -> m.getMetadata() != null
                        && m.getMetadata().get(key) != null
                        && m.getMetadata().get(key).toString().equals(value))
                .sorted((a, b) -> {
                    Instant ta = a.getTimestamp();
                    Instant tb = b.getTimestamp();
                    if (ta == null && tb == null) return 0;
                    if (ta == null) return -1;
                    if (tb == null) return 1;
                    return ta.compareTo(tb);
                })
                .limit(limit > 0 ? limit : Long.MAX_VALUE)
                .toList();
    }

    @Override
    public List<MessageWithConversation> executeQuery(String query, int limit) {
        String dsl = translateLegacyQuery(query);
        return searchAndHydrate(dsl, sortBySeqAsc(), limit, false);
    }

    @Override
    public PageResult<MessageWithConversation> pageByConversation(String conversationId, int page, int size) {
        if (page < 1) page = 1;
        if (size < 1) size = 10;
        String dsl = "@conversationId:{" + escapeTag(conversationId) + "}";
        // 先查 total：LIMIT 0 0 返回 total 不返回 doc
        long total = countByQuery(dsl);
        if (total == 0) {
            return PageResult.of(Collections.emptyList(), 0, page, size);
        }
        long start = (long) (page - 1) * size;
        List<String> args = new ArrayList<>();
        args.add(props.getRedis().getIndexName());
        args.add(dsl);
        args.add("SORTBY");
        args.add("seq");
        args.add("ASC");
        args.add("LIMIT");
        args.add(String.valueOf(start));
        args.add(String.valueOf(size));
        FtSearchResult result = commands.ftSearch(args);
        List<MessageWithConversation> records = hydrateDocs(result.getDocs());
        return PageResult.of(records, total, page, size);
    }

    @Override
    public long countByConversation(String conversationId) {
        String dsl = "@conversationId:{" + escapeTag(conversationId) + "}";
        return countByQuery(dsl);
    }

    // =========================================================
    // 压缩调度辅助
    // =========================================================

    public long lastActivity(String conversationId) {
        String v = commands.get(actKeyFor(conversationId));
        if (v == null) return 0L;
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    // =========================================================
    // 内部
    // =========================================================

    /** 按 conversationId 升序拉全部（不带分页，给压缩用）。 */
    private List<MessageWithConversation> findByConversationIdRaw(String conversationId) {
        String dsl = "@conversationId:{" + escapeTag(conversationId) + "}";
        return searchAndHydrate(dsl, sortBySeqAsc(), Integer.MAX_VALUE, false);
    }

    /** FT.SEARCH 子句拼装：SORTBY seq ASC */
    private List<String> sortBySeqAsc() {
        return List.of("SORTBY", "seq", "ASC");
    }

    /** FT.SEARCH 执行 → hydrate 回 MessageWithConversation 列表 */
    private List<MessageWithConversation> searchAndHydrate(String dsl, List<String> sortOrNull,
                                                           int limit, boolean withTotal) {
        List<String> args = new ArrayList<>();
        args.add(props.getRedis().getIndexName());
        args.add(dsl);
        if (sortOrNull != null && !sortOrNull.isEmpty()) {
            args.addAll(sortOrNull);
        }
        // 不要 N default 返回全部（让 hydrate 时再限制）
        if (limit > 0) {
            args.add("LIMIT");
            args.add("0");
            args.add(String.valueOf(limit));
        }
        FtSearchResult r = commands.ftSearch(args);
        return hydrateDocs(r.getDocs());
    }

    /** 解析 FT.SEARCH 拿到的 doc 列表为 MessageWithConversation */
    private List<MessageWithConversation> hydrateDocs(List<FtSearchResult.Doc> docs) {
        if (docs == null || docs.isEmpty()) return Collections.emptyList();
        List<MessageWithConversation> out = new ArrayList<>(docs.size());
        for (FtSearchResult.Doc d : docs) {
            String key = d.getKey();
            String jsonStr = commands.jsonGet(key);
            if (jsonStr == null || jsonStr.isBlank()) continue;
            try {
                MessageWithConversation mc = json.readValue(jsonStr, MessageWithConversation.class);
                if (mc != null) out.add(mc);
            } catch (Exception e) {
                log.warn("[memory] 文档反序列化失败 key={} err={}", key, e.toString());
            }
        }
        return out;
    }

    /** 仅返回命中 key 列表，不 hydrate body（用于 deleteByConversationId 等批量删除）。 */
    private List<String> searchKeys(String dsl, int limit) {
        List<String> args = new ArrayList<>();
        args.add(props.getRedis().getIndexName());
        args.add(dsl);
        // 不带 RETURN 时 FT.SEARCH 默认返回 [docKey, [fields]]；只取 key 时在 hydrate 处忽略
        if (limit < Integer.MAX_VALUE) {
            args.add("LIMIT");
            args.add("0");
            args.add(String.valueOf(limit));
        }
        FtSearchResult r = commands.ftSearch(args);
        List<String> keys = new ArrayList<>();
        for (FtSearchResult.Doc d : r.getDocs()) {
            if (d.getKey() != null) keys.add(d.getKey());
        }
        return keys;
    }

    /** FT.SEARCH LIMIT 0 0 仅拿 total */
    private long countByQuery(String dsl) {
        List<String> args = new ArrayList<>();
        args.add(props.getRedis().getIndexName());
        args.add(dsl);
        args.add("LIMIT");
        args.add("0");
        args.add("0");
        try {
            FtSearchResult r = commands.ftSearch(args);
            return r.getTotal();
        } catch (Exception e) {
            log.warn("[memory] FT.SEARCH 计数失败 dsl={} err={}", dsl, e.toString());
            return 0L;
        }
    }

    /**
     * 把项目自定义 DSL（{@code @type:USER @content:北京}）翻译为 RediSearch DSL（{@code @messageType:{USER} @content:北京}）。
     * 同时支持 {@code @summary:true} / {@code @conversationId:chat-xxx} / 直接透传 RediSearch 语法（返回原串）。
     */
    static String translateLegacyQuery(String raw) {
        if (raw == null || raw.isBlank()) return "*";
        String s = raw.trim();
        // 已看起来是 RediSearch DSL（含 [ ] { } - 等）就透传
        if (s.contains("[") || s.contains("|") || s.startsWith("*")) {
            return s;
        }
        Matcher mt = QUERY_TYPE.matcher(s);
        Matcher mc = QUERY_CONTENT.matcher(s);
        Matcher ms = QUERY_SUMMARY.matcher(s);
        Matcher mcv = QUERY_CONV.matcher(s);
        StringBuilder out = new StringBuilder();
        boolean first = true;
        if (mt.find()) {
            out.append("@messageType:{").append(escapeTag(mt.group(1).toUpperCase())).append('}');
            first = false;
        }
        if (mc.find()) {
            if (!first) out.append(' ');
            out.append("@content:").append(escapeText(mc.group(1)));
            first = false;
        }
        if (ms.find()) {
            if (!first) out.append(' ');
            out.append("@summary:{").append(escapeTag(ms.group(1))).append('}');
            first = false;
        }
        if (mcv.find()) {
            if (!first) out.append(' ');
            out.append("@conversationId:{").append(escapeTag(mcv.group(1))).append('}');
            first = false;
        }
        if (first) {
            // 不是已知模式：直接当关键词走 content 全文搜索
            return "@content:" + escapeText(s);
        }
        return out.toString();
    }

    /** RediSearch TAG 字段转义：值中的 - 必须转义，引号包住更稳妥 */
    static String escapeTag(String v) {
        if (v == null) return "";
        StringBuilder sb = new StringBuilder(v.length() + 2);
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '\\' || c == '"' || c == ',' || c == '{' || c == '}' || c == '(' || c == ')'
                    || c == '[' || c == ']' || c == '|' || c == ';' || c == '@' || c == ' '
                    || c == '-' || c == '>' || c == '<' || c == '=' || c == '~') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** RediSearch TEXT 字段字面量：空白用 "" 包住 + 转义内部 " \ */
    static String escapeText(String v) {
        if (v == null) return "\"\"";
        StringBuilder sb = new StringBuilder(v.length() + 4);
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '"' || c == '\\') sb.append('\\');
            sb.append(c);
        }
        return "\"" + sb + "\"";
    }

    Message toMessage(MessageWithConversation mc) {
        if (mc == null) return null;
        String text = mc.getContent() == null ? "" : mc.getContent();
        String type = mc.getMessageType() == null ? "" : mc.getMessageType();
        return switch (type) {
            case MessageWithConversation.TYPE_USER -> new UserMessage(text);
            case MessageWithConversation.TYPE_ASSISTANT -> new AssistantMessage(text);
            case MessageWithConversation.TYPE_SYSTEM -> new SystemMessage(text);
            default -> new UserMessage(text);
        };
    }

    private MessageWithConversation fromMessage(String conversationId, Message msg,
                                                Map<String, Object> metadata, Instant timestamp,
                                                long seq) {
        String type;
        if (msg instanceof UserMessage) {
            type = MessageWithConversation.TYPE_USER;
        } else if (msg instanceof AssistantMessage) {
            type = MessageWithConversation.TYPE_ASSISTANT;
        } else if (msg instanceof SystemMessage) {
            type = MessageWithConversation.TYPE_SYSTEM;
        } else {
            type = MessageWithConversation.TYPE_TOOL;
        }
        return MessageWithConversation.builder()
                .conversationId(conversationId)
                .messageType(type)
                .content(msg.getText())
                .metadata(metadata)
                .timestamp(timestamp)
                .seq(seq)
                .build();
    }

    // =========================================================
    // key 构造
    // =========================================================

    private String keyFor(String conversationId, long seq) {
        return props.getRedis().getKeyPrefix() + conversationId + ":" + seq;
    }

    private String seqKeyFor(String conversationId) {
        return props.getRedis().getKeyPrefix() + "seq:" + conversationId;
    }

    private String actKeyFor(String conversationId) {
        return props.getRedis().getKeyPrefix() + "act:" + conversationId;
    }

    /** 内部诊断用：当前会话的下一 seq（不占号） */
    public long peekSeq(String conversationId) {
        String v = commands.get(seqKeyFor(conversationId));
        if (v == null) return 0L;
        try { return Long.parseLong(v); } catch (NumberFormatException e) { return 0L; }
    }
}
