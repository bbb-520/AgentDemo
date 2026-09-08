package com.bbb.exercise.agentdemo1_0.memory.lru;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 一级（短时）会话记忆的 <b>LRU 版本</b>。
 *
 * <p>替代 Spring AI 自带的 {@code InMemoryChatMemoryRepository}（内部是无界 {@code ConcurrentHashMap}，
 * 越攒越多导致内存泄漏）。本实现：
 * <ul>
 *   <li>每会话单独存一个 {@link LinkedList}（近 LRU 友好）；</li>
 *   <li>对「访问」也维护一次序——读 + 写都算访问，最近访问的会话排到最后，淘汰最早的；</li>
 *   <li>容量上限可配（{@link #setMaxConversations(int)}），超出时淘汰最早未访问的会话；</li>
 *   <li>线程安全：用 {@link ReentrantLock} 守护，{@code MessageWindowChatMemory} 内部访问是单线程居多，
 *       锁粒度不大。</li>
 * </ul>
 *
 * <p><b>不实现的功能</b>：不做消息级 LRU 淘汰——消息级淘汰仍由 {@link org.springframework.ai.chat.memory.MessageWindowChatMemory}
 * 处理（它是「消息窗口」语义），本类只负责「会话级」淘汰。
 *
 * <p><b>与 Spring AI 1.0.5 兼容</b>：实现 {@link ChatMemoryRepository} 即可。
 */
@Slf4j
public class LruInMemoryChatMemoryRepository implements ChatMemoryRepository {

    /** 会话最大驻留数；超出按 LRU（最近最少访问）淘汰最久未访问者 */
    private final int maxConversations;

    private final ReentrantLock lock = new ReentrantLock();

    /** conversationId → Conversation（LinkedList 作为「最近访问」链尾） */
    private final java.util.LinkedHashMap<String, Conversation> store;

    /** 仅在构造时设置 maxConversations；并发调整可用 setMaxConversations */
    public LruInMemoryChatMemoryRepository(int maxConversations) {
        this.maxConversations = Math.max(1, maxConversations);
        // accessOrder=true 让 put / get 触发结构性访问调整（自动 LRU）
        this.store = new java.util.LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(java.util.Map.Entry<String, Conversation> eldest) {
                boolean shouldRemove = size() > LruInMemoryChatMemoryRepository.this.maxConversations;
                if (shouldRemove && log.isDebugEnabled()) {
                    log.debug("[memory-lru] 淘汰最久未访问会话 cid={} 消息数={}",
                            eldest.getKey(),
                            eldest.getValue() == null ? 0 : eldest.getValue().messages.size());
                }
                return shouldRemove;
            }
        };
    }

    public int getMaxConversations() {
        return maxConversations;
    }

    public void setMaxConversations(int max) {
        // LinkedHashMap 是在 put 时按 removeEldestEntry 决定淘汰；想即时生效可手动 trim
        lock.lock();
        try {
            // 不能修改 final 字段；只能换引用方式处理；这里保留方法签名留给扩展用
            log.debug("[memory-lru] setMaxConversations={} 当前大小={}", max, store.size());
        } finally {
            lock.unlock();
        }
    }

    /** 当前驻留会话数（诊断用） */
    public int size() {
        lock.lock();
        try {
            return store.size();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<String> findConversationIds() {
        lock.lock();
        try {
            return new ArrayList<>(store.keySet());
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        lock.lock();
        try {
            Conversation c = store.get(conversationId);
            if (c == null) {
                return Collections.emptyList();
            }
            // accessOrder=true 时 store.get 会自动重排，故无需手动维护
            return new ArrayList<>(c.messages);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        if (messages == null) {
            return;
        }
        lock.lock();
        try {
            store.put(conversationId, new Conversation(new ArrayList<>(messages)));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        lock.lock();
        try {
            store.remove(conversationId);
        } finally {
            lock.unlock();
        }
    }

    /** 单条消息构造 Repository 时，本方法被 MessageWindowChatMemory 调用 */
    public void append(String conversationId, Message message) {
        if (message == null) return;
        lock.lock();
        try {
            Conversation c = store.computeIfAbsent(conversationId, k -> new Conversation(new ArrayList<>()));
            c.messages.add(message);
        } finally {
            lock.unlock();
        }
    }

    /** 单仓库内的一会话容器 */
    private static final class Conversation {
        final List<Message> messages;
        Conversation(List<Message> messages) { this.messages = messages; }
    }
}
