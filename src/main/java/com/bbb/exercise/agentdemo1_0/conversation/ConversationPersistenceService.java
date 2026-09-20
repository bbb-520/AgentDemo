package com.bbb.exercise.agentdemo1_0.conversation;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bbb.exercise.agentdemo1_0.identity.ChatIdentity;
import com.bbb.exercise.agentdemo1_0.utils.ConversationKeys;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** 会话归属和长期消息存储。 */
@Service
public class ConversationPersistenceService {
    private final ConversationMapper conversationMapper;
    private final ChatMessageMapper messageMapper;

    public ConversationPersistenceService(ConversationMapper conversationMapper,
                                           ChatMessageMapper messageMapper) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
    }

    @Transactional
    public ConversationSession openOrCreate(String requestedId, ChatIdentity identity) {
        final String conversationId;
        try {
            conversationId = ConversationKeys.requireExistingOrNull(requestedId);
        } catch (IllegalArgumentException e) {
            throw new ConversationRequestException(400, e.getMessage());
        }
        if (conversationId == null) {
            ConversationEntity entity = new ConversationEntity();
            entity.setTenantId(identity.tenantId());
            entity.setUserId(identity.userId());
            entity.setConversationId(ConversationKeys.newConversationId());
            entity.setTitle("新会话");
            entity.setStatus(1);
            entity.setCreatedAt(LocalDateTime.now());
            entity.setUpdatedAt(entity.getCreatedAt());
            conversationMapper.insert(entity);
            return new ConversationSession(entity.getId(), entity.getConversationId(), identity, true);
        }
        ConversationEntity entity = conversationMapper.selectOne(Wrappers.<ConversationEntity>lambdaQuery()
                .eq(ConversationEntity::getTenantId, identity.tenantId())
                .eq(ConversationEntity::getUserId, identity.userId())
                .eq(ConversationEntity::getConversationId, conversationId)
                .eq(ConversationEntity::getStatus, 1));
        if (entity == null) {
            throw new ConversationRequestException(404, "会话不存在或不属于当前用户");
        }
        return new ConversationSession(entity.getId(), entity.getConversationId(), identity, false);
    }

    @Transactional
    public void appendUserMessage(ConversationSession session, String content) {
        insertMessage(session, "user", content, true);
    }

    @Transactional
    public void appendAssistantMessage(ConversationSession session, String content, boolean completed) {
        if (content == null || content.isEmpty()) return;
        insertMessage(session, "assistant", content, completed);
    }

    private void insertMessage(ConversationSession session, String role, String content, boolean completed) {
        ChatMessageEntity message = new ChatMessageEntity();
        message.setConversationDbId(session.databaseId());
        message.setRole(role);
        message.setContent(content);
        message.setCompleted(completed ? 1 : 0);
        message.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(message);
        ConversationEntity update = new ConversationEntity();
        update.setId(session.databaseId());
        update.setUpdatedAt(message.getCreatedAt());
        conversationMapper.updateById(update);
    }

    public static final class ConversationRequestException extends RuntimeException {
        private final int status;
        public ConversationRequestException(int status, String message) {
            super(message);
            this.status = status;
        }
        public int status() { return status; }
    }
}
