package com.bbb.exercise.agentdemo1_0.chat;

import com.bbb.exercise.agentdemo1_0.conversation.ConversationPersistenceService;
import com.bbb.exercise.agentdemo1_0.conversation.ConversationSession;
import com.bbb.exercise.agentdemo1_0.dto.ChatAttachmentRequest;
import com.bbb.exercise.agentdemo1_0.enums.ChatEventTypeEnum;
import com.bbb.exercise.agentdemo1_0.identity.ChatIdentity;
import com.bbb.exercise.agentdemo1_0.image.ImageJobService;
import com.bbb.exercise.agentdemo1_0.vo.ChatEventVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A chat turn creates exactly one image remix job from one uploaded image. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {
    private static final int MAX_QUESTION_LENGTH = 4000;
    private static final String DEFAULT_PROMPT = "请根据这张照片进行一次有创意的二次生成。";

    private final ConversationPersistenceService conversations;
    private final ImageJobService imageJobs;

    public Mono<ConversationSession> openConversation(String sessionId, ChatIdentity identity) {
        return Mono.fromCallable(() -> conversations.openOrCreate(sessionId, identity))
                .subscribeOn(Schedulers.boundedElastic());
    }

    public Flux<ChatEventVO> chat(String question, ConversationSession session,
                                  List<ChatAttachmentRequest> attachments) {
        List<ChatAttachmentRequest> images = attachments == null ? List.of() : attachments;
        String invalid = validateQuestion(question, !images.isEmpty());
        if (invalid != null) return Flux.just(errorEvent(invalid), stopEvent());
        if (images.size() != 1) return Flux.just(errorEvent("每次只支持一张图片"), stopEvent());

        String prompt = question == null || question.isBlank() ? DEFAULT_PROMPT : question.trim();
        String acknowledgement = "已收到照片，正在后台生成一张图片。完成后结果会回到当前对话。";
        String conversationId = session.conversationId();
        Mono<ImageJobService.JobView> create = Mono.fromCallable(() -> {
            ImageJobService.JobView job = imageJobs.create(session.identity(), conversationId, prompt, images);
            conversations.appendUserMessage(session, prompt);
            conversations.appendAssistantMessage(session, acknowledgement, true);
            return job;
        }).subscribeOn(Schedulers.boundedElastic());

        return Flux.concat(Flux.just(sessionInfoEvent(conversationId, session.created())),
                        create.flatMapMany(job -> Flux.just(imageJobEvent(job), dataEvent(acknowledgement), stopEvent())))
                .onErrorResume(error -> {
                    log.warn("[chat] image job creation failed cid={} reason={}", conversationId, error.toString());
                    return Flux.just(errorEvent("图片任务创建失败：" + errorMessage(error)), stopEvent());
                });
    }

    public static String validateQuestion(String question, boolean hasAttachments) {
        if (!hasAttachments) return "请先上传图片后再开始创作";
        if (question != null && question.length() > MAX_QUESTION_LENGTH) {
            return "问题过长（上限 " + MAX_QUESTION_LENGTH + " 字符）";
        }
        return null;
    }

    private static String errorMessage(Throwable error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.isBlank() ? String.valueOf(error) : message;
    }

    private static ChatEventVO dataEvent(String text) {
        return ChatEventVO.builder().eventType(ChatEventTypeEnum.DATA.getValue()).eventData(text).build();
    }

    private static ChatEventVO imageJobEvent(ImageJobService.JobView job) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("jobId", job.jobId());
        data.put("status", job.status());
        data.put("mode", job.mode());
        data.put("createdAt", job.createdAt());
        return ChatEventVO.builder().eventType(ChatEventTypeEnum.IMAGE_JOB.getValue()).eventData(data).build();
    }

    private static ChatEventVO stopEvent() {
        return ChatEventVO.builder().eventType(ChatEventTypeEnum.STOP.getValue()).build();
    }

    private static ChatEventVO errorEvent(String message) {
        return ChatEventVO.builder().eventType(ChatEventTypeEnum.ERROR.getValue()).eventData(message).build();
    }

    private static ChatEventVO sessionInfoEvent(String conversationId, boolean created) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("conversationId", conversationId);
        data.put("created", created);
        data.put("timestamp", System.currentTimeMillis());
        return ChatEventVO.builder().eventType(ChatEventTypeEnum.SESSION_INFO.getValue()).eventData(data).build();
    }
}
