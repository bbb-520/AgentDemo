package com.bbb.exercise.agentdemo1_0.memory.redis8;

import com.bbb.exercise.agentdemo1_0.config.ChatMemoryProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 旧存储清理器：Redis 8 改造启动期，<b>一次性</b>把旧版本的 Sorted Set 结构删除。
 *
 * <p><b>背景</b>：改造前的二级存储是 {@code Sorted Set：chat:memory:<cid>}（member = "seq:{json}"），
 * 改造后改为<b>每条消息一个 JSON 文档：{@code chat:memory:<cid>:<seq>}</b>。
 * 由于两种结构的 key 形式完全不同（前者 1 会话 1 key，后者 1 消息 1 key），不做兼容迁移——
 * 旧 key 直接删除，演示期间产生的数据可丢弃。
 *
 * <p><b>清理范围</b>（按 {@code chat.memory.redis.key-prefix} 配置）：
 * <ul>
 *   <li>Sorted Set：{@code chat:memory:*} 类型为 zset 的 key（旧消息主体）</li>
 *   <li>String：{@code chat:memory:seq:*} （旧序号自增器）</li>
 *   <li>String：{@code chat:memory:act:*} （旧活跃时间戳）</li>
 * </ul>
 *
 * <p><b>开关</b>：{@code chat.memory.redis.cleanup-legacy-on-startup}，默认 true。
 * 关闭后保留旧 key（但新写入不再走旧结构，后续可手工清理）。
 *
 * <p><b>幂等</b>：重复执行不会报错（旧 key 已被删自然 SCAN 不命中）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataMigrator {

    private final RedisJsonCommands commands;
    private final ChatMemoryProperties props;

    @PostConstruct
    public void cleanupOnStartup() {
        if (!props.getRedis().isCleanupLegacyOnStartup()) {
            log.info("[redis8] 旧结构清理已关闭（chat.memory.redis.cleanup-legacy-on-startup=false）");
            return;
        }
        try {
            int deleted = cleanupLegacy();
            log.warn("[redis8] 已清理 {} 条旧结构 key（前 Sorted Set 会话 + seq/act 字符串键）", deleted);
        } catch (Exception e) {
            log.error("[redis8] 旧结构清理异常", e);
        }
    }

    /**
     * 用 SCAN（不是 KEYS）扫描所有 {@code key-prefix*} 的旧结构 key，逐条 DEL。
     * 返回删除总数。重复执行幂等。
     */
    int cleanupLegacy() {
        int deleted = 0;
        String prefix = props.getRedis().getKeyPrefix();
        // ① Sorted Set（旧消息主体）
        deleted += scanAndDel(prefix + "*", "zset");
        // ② seq:*（旧序号键）
        deleted += scanAndDel(prefix + "seq:*", null);
        // ③ act:*（旧活跃时间键）
        deleted += scanAndDel(prefix + "act:*", null);
        return deleted;
    }

    private int scanAndDel(String pattern, String type) {
        int count = 0;
        String cursor = "0";
        do {
            List<Object> raw = commands.scanOnce(cursor, pattern, 200, type);
            cursor = raw.isEmpty() ? "0" : raw.get(0).toString();
            if (raw.size() < 2) continue;
            @SuppressWarnings("unchecked")
            List<Object> keys = (List<Object>) raw.get(1);
            List<String> delKeys = new ArrayList<>(keys.size());
            for (Object k : keys) {
                if (k != null) delKeys.add(k.toString());
            }
            if (!delKeys.isEmpty()) {
                Long n = commands.del(delKeys.toArray(new String[0]));
                count += (n == null ? 0 : n.intValue());
            }
        } while (!"0".equals(cursor));
        return count;
    }

    /** 列出当前 Redis 中所有仍存在的旧结构 key（运维辅助）。 */
    public List<String> listLegacyKeys() {
        String prefix = props.getRedis().getKeyPrefix();
        List<String> all = new ArrayList<>();
        all.addAll(scanAll(prefix + "*", "zset"));
        all.addAll(scanAll(prefix + "seq:*", null));
        all.addAll(scanAll(prefix + "act:*", null));
        return all;
    }

    private List<String> scanAll(String pattern, String type) {
        List<String> out = new ArrayList<>();
        String cursor = "0";
        do {
            List<Object> raw = commands.scanOnce(cursor, pattern, 200, type);
            if (raw.isEmpty()) {
                break;
            }
            cursor = raw.get(0) == null ? "0" : raw.get(0).toString();
            if (raw.size() < 2) {
                continue;
            }
            @SuppressWarnings("unchecked")
            List<Object> keys = (List<Object>) raw.get(1);
            for (Object k : keys) {
                if (k != null) {
                    out.add(k.toString());
                }
            }
        } while (!"0".equals(cursor));
        return out;
    }
}
