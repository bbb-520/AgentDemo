package com.bbb.exercise.agentdemo1_0.memory;

import com.bbb.exercise.agentdemo1_0.config.ChatMemoryProperties;
import com.bbb.exercise.agentdemo1_0.dto.MessageWithConversation;
import com.bbb.exercise.agentdemo1_0.memory.redis8.RedisJsonCommands;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 会话记忆压缩服务（Redis 8 改造：ChatModel + Redis Streams 双轨调度）。
 *
 * <h3>职责</h3>
 * 对存储在 Redis 中的会话历史，在空闲 {@link ChatMemoryProperties.Summary#getIdleMinutes()}
 * 分钟后调用 LLM 生成摘要（SystemMessage），双写 Redis 与内存窗口，减少后续引用时的 token 用量。
 *
 * <h3>修复点（相对改造前）</h3>
 * <ul>
 *   <li><b>P0-1（不再污染 default 会话）</b>：原实现注入了
 *       {@code ChatClient}（挂了 {@code MessageChatMemoryAdvisor}），调 LLM 时会向 {@code default} 会话
 *       写入临时请求/响应，污染内存窗口。改造后改注 {@link ChatModel}（裸模型，无 Advisor），
 *       调用 → 写 summary SystemMessage 到目标 cid 的二级存储 + 内存窗口，干净独立；</li>
 *   <li><b>P2-8（重启不丢任务）</b>：原 {@link ScheduledExecutorService} 进程内调度，重启即丢未完成的压缩。
 *       改造后压缩任务源是 Redis Streams：每条任务用 {@code XADD} 入队，消费者组
 *       {@code XREADGROUP >} 模式拉取，{@code XACK} 确认。重启时 {@code XPENDING/XAUTOCLAIM} 可续；</li>
 *   <li>保留 Streams 关闭时的回退路径（进程内 Executor），方便单机调试。</li>
 * </ul>
 *
 * <h3>Streams 协议</h3>
 * <pre>
 *   XADD chat:memory:compress-tasks * conversationId &lt;cid&gt; enqueuedAt &lt;epochMs&gt;
 * </pre>
 *
 * <h3>扫描触发</h3>
 * 仍由 {@link ScheduledExecutorService} 每 {@code scan-interval-seconds} 扫一遍，区别是：
 * <ul>
 *   <li>Streams 模式：扫描结果只 XADD 入队（轻），由消费者慢慢消化；</li>
 *   <li>非 Streams 模式：扫描 + 同步压缩（兼容原行为）。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemoryCompressionService {

    private final RedisChatMemoryRepository redisRepo;
    private final RedisJsonCommands commands;
    private final ChatModel chatModel;
    private final ChatMemory chatMemory;
    private final ChatMemoryProperties props;

    private ScheduledExecutorService scanner;
    private ScheduledExecutorService consumer;
    private volatile boolean running = true;

    @PostConstruct
    public void start() {
        if (!props.getSummary().isEnabled()) {
            log.info("[memory] 压缩调度未启用（chat.memory.summary.enabled=false）");
            return;
        }
        // ① 扫描器：每 N 秒检查活跃会话，产生压缩任务
        int interval = Math.max(5, props.getSummary().getScanIntervalSeconds());
        scanner = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "memory-scan");
            t.setDaemon(true);
            return t;
        });
        scanner.scheduleAtFixedRate(this::scanSafely, interval, interval, TimeUnit.SECONDS);

        // ② 消费者：Streams 模式下拉取任务并压缩
        if (props.getRedis().getStreams().isEnabled()) {
            String stream = props.getRedis().getStreams().getKey();
            String group = props.getRedis().getStreams().getConsumerGroup();
            try {
                commands.ensureGroup(stream, group, false);
            } catch (Exception e) {
                log.warn("[memory] Streams 消费者组创建失败 stream={} group={} err={}", stream, group, e.toString());
            }
            consumer = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "memory-consume");
                t.setDaemon(true);
                return t;
            });
            long pollMs = props.getRedis().getStreams().getPollIntervalMs();
            consumer.scheduleWithFixedDelay(this::consumeOnce, 5, Math.max(1, pollMs / 1000), TimeUnit.SECONDS);
            log.info("[memory] Streams 消费器已启动 stream={} group={} poll={}s",
                    stream, group, pollMs / 1000);
        }

        log.info("[memory] 压缩调度已启动 scan={}s idle={}min keepRecent={} maxLen={} streams={}",
                interval,
                props.getSummary().getIdleMinutes(),
                props.getSummary().getKeepRecent(),
                props.getSummary().getMaxLength(),
                props.getRedis().getStreams().isEnabled());
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (scanner != null) scanner.shutdownNow();
        if (consumer != null) consumer.shutdownNow();
        log.info("[memory] 压缩调度已关闭");
    }

    // ============================ 扫描器 ============================

    private void scanSafely() {
        try {
            scan();
        } catch (Exception e) {
            log.error("[memory] 扫描异常", e);
        }
    }

    /**
     * 扫描所有会话：对"空闲超阈值"的会话根据模式产生压缩任务。
     * <ul>
     *   <li>Streams 模式：XADD 一个任务（轻量）</li>
     *   <li>非 Streams 模式：直接同步触发 summarize</li>
     * </ul>
     */
    private void scan() {
        long idleMs = TimeUnit.MINUTES.toMillis(props.getSummary().getIdleMinutes());
        long now = System.currentTimeMillis();
        for (String cid : redisRepo.findConversationIds()) {
            long last = redisRepo.lastActivity(cid);
            if (last <= 0) continue;
            if (now - last < idleMs) continue;
            try {
                if (props.getRedis().getStreams().isEnabled()) {
                    enqueueCompressTask(cid);
                } else {
                    summarize(cid);
                }
            } catch (Exception e) {
                log.warn("[memory] 扫描处理异常 cid={} err={}", cid, e.toString());
            }
        }
    }

    private void enqueueCompressTask(String conversationId) {
        // XADD task * conversationId <cid> enqueuedAt <now>
        List<String> fields = List.of(
                "conversationId", conversationId,
                "enqueuedAt", String.valueOf(System.currentTimeMillis())
        );
        try {
            commands.xAdd(props.getRedis().getStreams().getKey(), fields);
        } catch (Exception e) {
            log.warn("[memory] XADD 压缩任务失败 cid={} err={}", conversationId, e.toString());
        }
    }

    // ============================ 消费者 ============================

    private void consumeOnce() {
        if (!running) return;
        String stream = props.getRedis().getStreams().getKey();
        String group = props.getRedis().getStreams().getConsumerGroup();
        String consumerName = props.getRedis().getStreams().getConsumerName();
        int batch = Math.max(1, props.getRedis().getStreams().getBatchSize());
        try {
            List<Object> raw = commands.xReadGroup(stream, group, consumerName, batch,
                    props.getRedis().getStreams().getPollIntervalMs());
            if (raw.isEmpty()) {
                return;
            }
            // raw 结构：[ [streamName, [[id, [field, value, ...]], ...]] ]
            // 形式不定（nile 阻塞返回 []），解析要稳
            for (Object entry : raw) {
                if (!(entry instanceof List<?> outer) || outer.size() < 2) continue;
                Object streamInfo = outer.get(1);
                if (!(streamInfo instanceof List<?> entries)) continue;
                for (Object e : entries) {
                    if (!(e instanceof List<?> pair) || pair.size() < 2) continue;
                    String id = String.valueOf(pair.get(0));
                    Object fieldsObj = pair.get(1);
                    String cid = extractConversationId(fieldsObj);
                    try {
                        if (cid != null) summarize(cid);
                        commands.xAck(stream, group, id);
                    } catch (Exception ex) {
                        log.warn("[memory] 处理压缩任务失败 id={} cid={} err={}", id, cid, ex.toString());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[memory] XREADGROUP 拉取失败 err={}", e.toString());
        }
    }

    private static String extractConversationId(Object fieldsObj) {
        if (!(fieldsObj instanceof List<?> fields)) return null;
        for (int i = 0; i + 1 < fields.size(); i += 2) {
            if ("conversationId".equals(fields.get(i))) {
                return String.valueOf(fields.get(i + 1));
            }
        }
        return null;
    }

    // ============================ 压缩核心 ============================

    /**
     * 对指定会话执行压缩（可手动触发、扫描直接触发、Streams 消费触发共用）。
     *
     * @return 生成的摘要文本；满足跳过条件时为 {@code null}
     */
    public String summarize(String conversationId) {
        int keepRecent = props.getSummary().getKeepRecent();
        int maxLen = props.getSummary().getMaxLength();

        List<MessageWithConversation> all = redisRepo
                .pageByConversation(conversationId, 1, props.getRedis().getMaxMessagesPerConversation())
                .getRecords();
        if (all.size() <= keepRecent) {
            return null;
        }
        int lastSummaryIdx = -1;
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i).isSummary()) {
                lastSummaryIdx = i;
                break;
            }
        }
        int toCompressEnd = all.size() - keepRecent;
        int toCompressStart = lastSummaryIdx + 1;
        if (toCompressEnd <= toCompressStart) {
            return null;
        }
        List<MessageWithConversation> toCompress = all.subList(toCompressStart, toCompressEnd);
        String historyText = formatHistory(toCompress);
        if (historyText.isBlank()) return null;

        String prompt = buildPrompt(historyText, maxLen);
        String summary;
        try {
            // ============ 修复 P0-1 ============
            // 改用底层 ChatModel 直接发请求，**不走 ChatClient Advisor 管线**，避免把摘要请求/响应写到 "default" 会话。
            ChatResponse response = chatModel.call(new Prompt(
                    List.of(new SystemMessage("你是一位对话摘要助手，只输出纯文本。"),
                            new org.springframework.ai.chat.messages.UserMessage(prompt))));
            if (response == null || response.getResult() == null
                    || response.getResult().getOutput() == null) {
                return null;
            }
            summary = response.getResult().getOutput().getText();
        } catch (Exception e) {
            log.warn("[memory] 生成摘要失败 cid={} err={}", conversationId, e.toString());
            return null;
        }
        if (summary == null || summary.isBlank()) return null;
        summary = summary.trim();
        if (summary.length() > maxLen) {
            summary = summary.substring(0, maxLen);
        }

        Map<String, Object> meta = new HashMap<>();
        meta.put(MessageWithConversation.META_SUMMARY, true);
        meta.put(MessageWithConversation.META_SUMMARIZED_COUNT, toCompress.size());
        Instant rangeStart = toCompress.get(0).getTimestamp();
        Instant rangeEnd = toCompress.get(toCompress.size() - 1).getTimestamp();
        if (rangeStart != null) meta.put(MessageWithConversation.META_RANGE_START, rangeStart.toString());
        if (rangeEnd != null) meta.put(MessageWithConversation.META_RANGE_END, rangeEnd.toString());

        // 双写：Redis（带 metadata）+ 内存窗口
        SystemMessage summaryMsg = new SystemMessage("以下是此前对话的摘要：\n" + summary);
        redisRepo.appendMessage(conversationId, summaryMsg, meta);
        try {
            chatMemory.add(conversationId, List.of(summaryMsg));
        } catch (Exception e) {
            log.warn("[memory] 摘要写入内存窗口失败 cid={} err={}", conversationId, e.toString());
        }

        log.info("[memory] 压缩完成 cid={} summarized={} summaryLen={}",
                conversationId, toCompress.size(), summary.length());
        return summary;
    }

    /** 把待压缩消息格式化为「角色:内容」纯文本。 */
    private String formatHistory(List<MessageWithConversation> msgs) {
        StringBuilder sb = new StringBuilder();
        for (MessageWithConversation m : msgs) {
            if (m.getContent() == null || m.getContent().isBlank()) continue;
            String role = switch (m.getMessageType() == null ? "" : m.getMessageType()) {
                case MessageWithConversation.TYPE_USER -> "用户";
                case MessageWithConversation.TYPE_ASSISTANT -> "助手";
                case MessageWithConversation.TYPE_SYSTEM -> "系统";
                default -> "用户";
            };
            sb.append(role).append("：").append(m.getContent()).append('\n');
        }
        return sb.toString();
    }

    /** 摘要生成 prompt：强调保留关键事实与意图、丢弃寒暄与工具细节、纯文本、严格限长。 */
    private String buildPrompt(String historyText, int maxLen) {
        return """
                你是对话摘要助手。请将下面的多轮对话压缩为不超过 %d 字的摘要。

                要求：
                1. 用第三人称客观陈述，保留关键事实（地点、时间、数字、结论）与用户意图；
                2. 保留助手给出的重要答案要点，丢弃寒暄、套话、工具调用细节；
                3. 纯文本，不分点列表，不要"以下是摘要"之类开头；
                4. 严格不超过 %d 字。

                对话：
                %s
                """.formatted(maxLen, maxLen, historyText);
    }
}
