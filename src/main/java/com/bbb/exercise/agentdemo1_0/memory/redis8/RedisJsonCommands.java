package com.bbb.exercise.agentdemo1_0.memory.redis8;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Redis 8 原生命令的薄封装：JSON.SET / JSON.GET / FT.* / SCAN / XADD / XREADGROUP 等。
 *
 * <p>Spring Data Redis 只对「Redis 标准命令 + 数据结构类型操作」提供了高层 API；
 * RedisJSON 与 RediSearch（Query Engine）虽然在 Redis 8 中属于内置能力，但 Spring Data 的
 * {@code RedisConnection.execute(...)} 仍然是把它们当作「扩展命令」通过字节流发出，
 * 响应也是裸的 {@code List<Object>}。本类把这些底层细节包装成可被业务直接调用的同步方法。
 *
 * <p><b>返回值约定</b>：
 * <ul>
 *   <li>{@link #jsonSet(String, String)} 返回 Redis 的 {@code OK / null}；</li>
 *   <li>{@link #jsonGet(String)} 返回 JSON 字符串（不存在返回 {@code null}）；</li>
 *   <li>{@link #ftCreate(List)} / {@link #ftDropIndex(String)} / {@link #jsonDel(String)} 返回任意响应对象；</li>
 *   <li>{@link #ftIndexExists(String)} 返回 {@code true/false}；</li>
 *   <li>{@link #ftSearch(List)} 返回原始 {@link FtSearchResult}（已结构化为 total + 文档列表）；</li>
 *   <li>{@link #ftList()} 返回索引名字符串列表。</li>
 * </ul>
 *
 * <p><b>异常</b>：底层抛 {@link DataAccessException} 时直接外抛，调用方决定是否降级。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisJsonCommands {

    private final StringRedisTemplate redis;

    private static byte[] bytes(String s) {
        return s == null ? null : s.getBytes(StandardCharsets.UTF_8);
    }

    // ======================== JSON 文档 ========================

    /** JSON.SET key path value —— path 通常为 "$" */
    public Object jsonSet(String key, String path, String jsonValue) {
        return redis.execute((RedisConnection conn) -> conn.execute("JSON.SET",
                bytes(key), bytes(path), bytes(jsonValue)));
    }

    /** JSON.SET key $ json 简写（多数情况够用） */
    public Object jsonSet(String key, String jsonValue) {
        return jsonSet(key, "$", jsonValue);
    }

    /** JSON.GET key —— 返回 JSON 字符串（不存在返回 null） */
    public String jsonGet(String key) {
        Object resp = redis.execute((RedisConnection conn) -> conn.execute("JSON.GET", bytes(key)));
        if (resp == null) {
            return null;
        }
        if (resp instanceof byte[] b) {
            return new String(b, StandardCharsets.UTF_8);
        }
        return resp.toString();
    }

    /** JSON.DEL key —— 删除对应 JSON 文档；返回被删除的元素数（0 表示不存在） */
    public Object jsonDel(String key) {
        return redis.execute((RedisConnection conn) -> conn.execute("JSON.DEL", bytes(key)));
    }

    // ======================== FT (RediSearch / Query Engine) ========================

    public boolean ftIndexExists(String indexName) {
        @SuppressWarnings("unchecked")
        List<Object> resp = (List<Object>) redis.execute((RedisConnection conn) -> conn.execute("FT._LIST"));
        if (resp == null) return false;
        for (Object o : resp) {
            if (o == null) continue;
            if (indexName.equals(o.toString())) return true;
        }
        return false;
    }

    public Object ftCreate(List<String> args) {
        return redis.execute((RedisConnection conn) -> {
            byte[][] byteArgs = new byte[args.size()][];
            for (int i = 0; i < args.size(); i++) {
                byteArgs[i] = bytes(args.get(i));
            }
            return conn.execute("FT.CREATE", byteArgs);
        });
    }

    /** FT.DROPINDEX idx —— 不删除数据；FT.DROPINDEX idx DD 才删除数据 */
    public Object ftDropIndex(String indexName) {
        return redis.execute((RedisConnection conn) -> conn.execute("FT.DROPINDEX", bytes(indexName)));
    }

    public List<String> ftList() {
        @SuppressWarnings("unchecked")
        List<Object> resp = (List<Object>) redis.execute((RedisConnection conn) -> conn.execute("FT._LIST"));
        if (resp == null) return Collections.emptyList();
        List<String> out = new ArrayList<>(resp.size());
        for (Object o : resp) {
            if (o != null) out.add(o.toString());
        }
        return out;
    }

    /**
     * FT.SEARCH 解析结果，total=匹配数，docs=每条 {@link Doc}。
     * <p>{@code Doc.getKey()} 是文档 key（{@code chat:memory:<cid>:<seq>}），
     * {@code Doc.getFields()} 是字段值 Map（key 为字段名）。
     */
    public FtSearchResult ftSearch(List<String> args) {
        @SuppressWarnings("unchecked")
        List<Object> resp = (List<Object>) redis.execute((RedisConnection conn) -> {
            byte[][] byteArgs = new byte[args.size()][];
            for (int i = 0; i < args.size(); i++) {
                byteArgs[i] = bytes(args.get(i));
            }
            return conn.execute("FT.SEARCH", byteArgs);
        });
        return parseFtSearch(resp);
    }

    /**
     * FT.SEARCH 返回结构（Redis 8）：
     * <pre>
     *   [ total, docKey1, [ fieldName1, fieldValue1, fieldName2, fieldValue2, ... ],
     *     docKey2, [ fieldName1, fieldValue1, ... ], ... ]
     * </pre>
     * 当 FT.SEARCH 不带 LIMIT 时返回的是 List<Object>；带 LIMIT 时第二项起是 "docKey, [fields...]" 对，
     * 总长度 = 2 * hits + 1；无命中时只有 [0]。
     */
    static FtSearchResult parseFtSearch(List<Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return new FtSearchResult(0L, Collections.emptyList());
        }
        long total = parseLong(raw.get(0));
        List<FtSearchResult.Doc> docs = new ArrayList<>();
        int i = 1;
        while (i + 1 < raw.size()) {
            String docKey = bytesToString(raw.get(i));
            @SuppressWarnings("unchecked")
            List<Object> fields = (List<Object>) raw.get(i + 1);
            LinkedHashMap<String, String> map = new LinkedHashMap<>();
            if (fields != null) {
                for (int j = 0; j + 1 < fields.size(); j += 2) {
                    map.put(bytesToString(fields.get(j)), bytesToString(fields.get(j + 1)));
                }
            }
            docs.add(new FtSearchResult.Doc(docKey, map));
            i += 2;
        }
        return new FtSearchResult(total, docs);
    }

    // ======================== SCAN ========================

    /**
     * SCAN cursor MATCH pattern COUNT count [TYPE type] —— 返回 [nextCursor, [...keys]]。
     * TYPE 在 Redis 6+ 可用，本项目所有 chat:memory: key 都是 JSON 类型。
     */
    public List<Object> scanOnce(String cursor, String match, long count, String type) {
        @SuppressWarnings("unchecked")
        List<Object> resp = (List<Object>) redis.execute((RedisConnection conn) -> {
            byte[][] args;
            if (type == null) {
                args = new byte[][]{bytes(cursor), "MATCH".getBytes(StandardCharsets.UTF_8),
                        bytes(match), "COUNT".getBytes(StandardCharsets.UTF_8),
                        bytes(String.valueOf(count))};
            } else {
                args = new byte[][]{bytes(cursor), "MATCH".getBytes(StandardCharsets.UTF_8),
                        bytes(match), "COUNT".getBytes(StandardCharsets.UTF_8),
                        bytes(String.valueOf(count)), "TYPE".getBytes(StandardCharsets.UTF_8),
                        bytes(type)};
            }
            return conn.execute("SCAN", args);
        });
        return resp == null ? Collections.emptyList() : resp;
    }

    // ======================== Streams ========================

    /**
     * XADD stream * field1 value1 field2 value2 ...
     * <p>field/value 列表必须为偶数长度，奇数位为 field 名，偶数位为 value。
     */
    public String xAdd(String stream, List<String> fieldsAndValues) {
        if (fieldsAndValues == null || fieldsAndValues.isEmpty() || fieldsAndValues.size() % 2 != 0) {
            throw new IllegalArgumentException("XADD 字段必须为偶数个（field/value 成对）");
        }
        Object resp = redis.execute((RedisConnection conn) -> {
            byte[][] args = new byte[fieldsAndValues.size() + 2][];
            args[0] = bytes(stream);
            args[1] = "*".getBytes(StandardCharsets.UTF_8);
            for (int i = 0; i < fieldsAndValues.size(); i++) {
                args[i + 2] = bytes(fieldsAndValues.get(i));
            }
            return conn.execute("XADD", args);
        });
        return resp == null ? null : resp.toString();
    }

    /**
     * XGROUP CREATE stream group [MKSTREAM] [$] —— 创建消费者组。
     * 已存在报 BUSYGROUP，不抛错。
     */
    public void ensureGroup(String stream, String group, boolean mkStream) {
        try {
            redis.execute((RedisConnection conn) -> {
                byte[][] args;
                if (mkStream) {
                    args = new byte[][]{bytes(stream), "CREATE".getBytes(StandardCharsets.UTF_8),
                            bytes(group), "0".getBytes(StandardCharsets.UTF_8)};
                } else {
                    args = new byte[][]{bytes(stream), "CREATE".getBytes(StandardCharsets.UTF_8),
                            bytes(group), "$".getBytes(StandardCharsets.UTF_8)};
                }
                return conn.execute("XGROUP", args);
            });
        } catch (DataAccessException e) {
            if (e.getMessage() != null && e.getMessage().contains("BUSYGROUP")) {
                log.debug("[redis8] 消费者组已存在 stream={} group={}", stream, group);
                return;
            }
            throw e;
        }
    }

    /**
     * XREADGROUP GROUP group consumer COUNT count BLOCK ms STREAMS stream >
     * 返回结构：{@code [streamName, [[id, [field1, value1, ...]], ...]]} 或者 []（阻塞超时）。
     */
    public List<Object> xReadGroup(String stream, String group, String consumer,
                                   long count, long blockMs) {
        @SuppressWarnings("unchecked")
        List<Object> resp = (List<Object>) redis.execute((RedisConnection conn) -> {
            byte[][] args = new byte[][]{
                    "GROUP".getBytes(StandardCharsets.UTF_8), bytes(group), bytes(consumer),
                    "COUNT".getBytes(StandardCharsets.UTF_8), bytes(String.valueOf(count)),
                    "BLOCK".getBytes(StandardCharsets.UTF_8), bytes(String.valueOf(blockMs)),
                    "STREAMS".getBytes(StandardCharsets.UTF_8), bytes(stream),
                    ">".getBytes(StandardCharsets.UTF_8)
            };
            return conn.execute("XREADGROUP", args);
        });
        return resp == null ? Collections.emptyList() : resp;
    }

    /** XACK stream group id —— 确认消费完成，消息从 PEL 移除 */
    public long xAck(String stream, String group, String id) {
        Object resp = redis.execute((RedisConnection conn) -> conn.execute("XACK",
                bytes(stream), bytes(group), bytes(id)));
        if (resp == null) return 0L;
        return parseLong(resp);
    }

    /** XLEN stream —— 当前 Stream 长度 */
    public long xLen(String stream) {
        Object resp = redis.execute((RedisConnection conn) -> conn.execute("XLEN", bytes(stream)));
        return resp == null ? 0L : parseLong(resp);
    }

    // ======================== 通用 ========================

    public long incrBy(String key, long delta) {
        Object resp = redis.execute((RedisConnection conn) -> conn.execute("INCRBY",
                bytes(key), bytes(String.valueOf(delta))));
        return resp == null ? 0L : parseLong(resp);
    }

    public void expire(String key, Duration ttl) {
        Object resp = redis.execute((RedisConnection conn) -> conn.execute("EXPIRE",
                bytes(key), bytes(String.valueOf(ttl.toSeconds()))));
        // 忽略返回值（0=key 不存在；1=成功）
        if (resp == null) {
            log.debug("[redis8] EXPIRE 返回 null key={}", key);
        }
    }

    public void set(String key, String value, Duration ttl) {
        redis.execute((RedisConnection conn) -> conn.execute("SET",
                bytes(key), bytes(value),
                "EX".getBytes(StandardCharsets.UTF_8),
                bytes(String.valueOf(ttl.toSeconds()))));
    }

    public String get(String key) {
        Object resp = redis.execute((RedisConnection conn) -> conn.execute("GET", bytes(key)));
        if (resp == null) return null;
        if (resp instanceof byte[] b) return new String(b, StandardCharsets.UTF_8);
        return resp.toString();
    }

    public Long del(String key) {
        Boolean b = redis.delete(key);
        return Boolean.TRUE.equals(b) ? 1L : 0L;
    }

    /** 批量删除多个 key，返回被删除的数量 */
    public Long del(String... keys) {
        if (keys == null || keys.length == 0) return 0L;
        Long n = 0L;
        for (String k : keys) {
            Boolean b = redis.delete(k);
            if (Boolean.TRUE.equals(b)) n++;
        }
        return n;
    }

    static String bytesToString(Object o) {
        if (o == null) return "";
        if (o instanceof byte[] b) return new String(b, StandardCharsets.UTF_8);
        return o.toString();
    }

    static long parseLong(Object o) {
        if (o == null) return 0L;
        if (o instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(o.toString());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** FT.SEARCH 结果封装（业务层用） */
    public static final class FtSearchResult {
        private final long total;
        private final List<Doc> docs;

        public FtSearchResult(long total, List<Doc> docs) {
            this.total = total;
            this.docs = docs == null ? Collections.emptyList() : docs;
        }

        public long getTotal() { return total; }
        public List<Doc> getDocs() { return docs; }

        public static final class Doc {
            private final String key;
            private final java.util.Map<String, String> fields;

            public Doc(String key, java.util.Map<String, String> fields) {
                this.key = key;
                this.fields = fields;
            }

            public String getKey() { return key; }
            public java.util.Map<String, String> getFields() { return fields; }
        }
    }
}
