package com.bbb.exercise.agentdemo1_0.chat;

import com.bbb.exercise.agentdemo1_0.enums.ChatEventTypeEnum;
import com.bbb.exercise.agentdemo1_0.auth.UserApiKeyService;
import com.bbb.exercise.agentdemo1_0.conversation.ConversationPersistenceService;
import com.bbb.exercise.agentdemo1_0.conversation.ConversationSession;
import com.bbb.exercise.agentdemo1_0.dto.ChatAttachmentRequest;
import com.bbb.exercise.agentdemo1_0.image.ImageAssetService;
import com.bbb.exercise.agentdemo1_0.image.ImageJobService;
import com.bbb.exercise.agentdemo1_0.identity.ChatIdentity;
import com.bbb.exercise.agentdemo1_0.oss.OssStorageService;
import com.bbb.exercise.agentdemo1_0.utils.ConversationKeys;
import com.bbb.exercise.agentdemo1_0.utils.StringUtils;
import com.bbb.exercise.agentdemo1_0.vo.ChatEventVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.content.Media;
import org.springframework.util.MimeTypeUtils;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


/**
 * 对话核心服务：负责参数校验、调用模型、编排事件流。
 *
 * <p>一次流式对话的事件顺序（对外契约）：<br>
 * {@code SESSION_INFO} → {@code DATA} × N → {@code STOP}
 *
 * <p>短期上下文（Redis 持久化 + 内存滑窗）由 {@code MessageChatMemoryAdvisor} 自动读写；
 * 会话归属和完整消息由 MyBatis-Plus 写入 MySQL；
 * 工具调用由 Spring AI 的 {@code ToolCallingAdvisor} 自动完成「调用工具 → 回传结果 → 再问模型」的循环，
 * 调用过程不对外直播，模型只把最终答案以 {@code DATA} 事件输出。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    /** 单次提问的最大字符数（防御性上限：避免超长输入直接进模型带来的成本与滥用风险） */
    private static final int MAX_QUESTION_LENGTH = 4000;

    private final UserChatClientFactory chatClientFactory;
    private final UserApiKeyService userApiKeys;
    private final ConversationPersistenceService conversationPersistenceService;
    private final ImageJobService imageJobs;
    private final ImageAssetService imageAssets;
    private final OssStorageService storage;
    private final ChatMemory chatMemory;


    /**
     * 在开始 SSE 之前创建新会话或校验已有会话的归属。
     * 数据库操作放到 boundedElastic，避免阻塞 WebFlux 事件循环。
     */
    public Mono<ConversationSession> openConversation(String sessionId, ChatIdentity identity) {
        return Mono.fromCallable(() -> conversationPersistenceService.openOrCreate(sessionId, identity))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 流式对话：把模型的正文增量转成 {@code DATA} 事件持续推送，并保存长期消息。
     *
     * <p>流程：校验参数 → 发起模型流式调用 → 首发会话信息 → 逐条推送正文 → 追发结束事件。
     *
     * @param question  用户问题
     * @param session 已完成身份校验的会话
     * @return 事件流；参数非法或异常时返回「错误事件 + 结束事件」而非抛异常，保证 HTTP 层始终返回 200 的 SSE
     */
    public Flux<ChatEventVO> chat(String question, ConversationSession session) {
        return chat(question, session, List.of());
    }

    public Flux<ChatEventVO> chat(String question, ConversationSession session,
                                  List<ChatAttachmentRequest> attachments) {
        List<ChatAttachmentRequest> safeAttachments = attachments == null ? List.of() : attachments;
        String invalid = validateQuestion(question, !safeAttachments.isEmpty());
        if (invalid != null) {
            return Flux.just(errorEvent(invalid), stopEvent());
        }
        String memoryKey = ConversationKeys.memoryKey(session.identity(), session.conversationId());
        // Redis-backed memory is blocking; inspect it off the WebFlux event loop before deciding
        // whether this request completes a staged image/text pair.
        return Mono.fromCallable(() -> pendingContext(memoryKey))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(pending -> route(question, session, safeAttachments, pending, memoryKey))
                .onErrorResume(error -> Flux.just(errorEvent("读取会话上下文失败：" + errorMessage(error)), stopEvent()));
    }

    private Flux<ChatEventVO> route(String question, ConversationSession session,
                                    List<ChatAttachmentRequest> attachments, PendingContext pending,
                                    String memoryKey) {
        boolean hasQuestion = StringUtils.isNotBlank(question);
        boolean hasAttachments = !attachments.isEmpty();

        if (hasAttachments) {
            // A photo plus an explicit generation brief keeps the existing asynchronous
            // image pipeline. All other photo+text requests go to a vision-capable LLM.
            String effectiveQuestion = hasQuestion ? question : pending.text();
            if (StringUtils.isNotBlank(effectiveQuestion) && isImageGenerationRequest(effectiveQuestion)) {
                return chatWithImage(effectiveQuestion, session,
                        hasQuestion ? attachments : List.of(pending.attachment()));
            }
            if (!hasQuestion && !StringUtils.isNotBlank(pending.text())) {
                return stageAttachment(session, memoryKey, attachments);
            }
            return Mono.fromCallable(() -> userApiKeys.get(session.identity()))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMapMany(keys -> chatWithMultimodal(
                            StringUtils.isNotBlank(effectiveQuestion) ? effectiveQuestion : "请根据已上传的图片完成任务。",
                            session, keys, attachments,
                            hasQuestion ? null : pending.text()));
        }

        if (!hasQuestion) {
            return Flux.just(errorEvent("请上传图片或输入问题"), stopEvent());
        }

        if (pending.hasImage()) {
            if (isImageGenerationRequest(question)) {
                return chatWithImage(question, session, List.of(pending.attachment()));
            }
            return Mono.fromCallable(() -> userApiKeys.get(session.identity()))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMapMany(keys -> chatWithMultimodal(question, session, keys,
                            List.of(pending.attachment()), null));
        }
        if (isLikelyImageInstruction(question)) {
            return stageText(session, memoryKey, question);
        }
        return Mono.fromCallable(() -> userApiKeys.get(session.identity()))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(keys -> chatWithKeys(question, session, keys));
    }

    /** Store an image in conversation memory without invoking a model until the next turn. */
    private Flux<ChatEventVO> stageAttachment(ConversationSession session, String memoryKey,
                                              List<ChatAttachmentRequest> attachments) {
        String acknowledgement = "已收到图片，请继续告诉我想如何处理；收到下一步指令后我再开始作业。";
        return Mono.fromCallable(() -> {
                    imageAssets.requireReady(session.identity(), attachments.get(0).getAssetId());
                    return true;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(ignored -> stagePending(session, memoryKey,
                        UserMessage.builder().text("用户上传了一张图片，等待下一步指令。")
                                .metadata(pendingMetadata(attachments.get(0))).build(),
                        "已上传图片，等待下一步指令。", acknowledgement));
    }

    /** Store an image-related brief until a photo is supplied on a later turn. */
    private Flux<ChatEventVO> stageText(ConversationSession session, String memoryKey, String question) {
        String acknowledgement = "已记住这条图片处理要求，请上传图片或继续补充；信息齐全后我再开始作业。";
        return stagePending(session, memoryKey,
                UserMessage.builder().text(question).metadata(Map.of("pending", true, "pendingKind", "text")).build(),
                question, acknowledgement);
    }

    private Flux<ChatEventVO> stagePending(ConversationSession session, String memoryKey,
                                           UserMessage message, String persistedText,
                                           String acknowledgement) {
        Mono<Void> saveMemory = Mono.<Void>fromRunnable(() -> chatMemory.add(memoryKey, message))
                .subscribeOn(Schedulers.boundedElastic());
        Mono<Void> saveUserMessage = Mono.<Void>fromRunnable(() ->
                        conversationPersistenceService.appendUserMessage(session, persistedText))
                .subscribeOn(Schedulers.boundedElastic());
        return Flux.concat(
                        Flux.just(sessionInfoEvent(session.conversationId(), session.created())),
                        saveMemory.then(saveUserMessage).thenMany(Flux.just(dataEvent(acknowledgement), stopEvent())))
                .onErrorResume(error -> Flux.just(errorEvent("暂存图片或要求失败：" + errorMessage(error)), stopEvent()));
    }

    /** 明确的创作/重绘请求走确定性的后台任务，结果再回到当前会话。 */
    private Flux<ChatEventVO> chatWithImage(String question, ConversationSession session,
                                            List<ChatAttachmentRequest> attachments) {
        String conversationId = session.conversationId();
        String acknowledgement = "已收到照片，正在后台生成。完成后结果会回到当前对话。";
        Mono<Void> saveUserMessage = Mono.<Void>fromRunnable(() ->
                        conversationPersistenceService.appendUserMessage(session, question))
                .subscribeOn(Schedulers.boundedElastic());
        Mono<ImageJobService.JobView> create = Mono.fromCallable(() ->
                        imageJobs.create(session.identity(), conversationId, question, attachments))
                .subscribeOn(Schedulers.boundedElastic());

        return Flux.concat(
                        Flux.just(sessionInfoEvent(conversationId, session.created())),
                        saveUserMessage.thenMany(create.flatMapMany(job -> {
                            Mono<Void> saveAssistant = Mono.<Void>fromRunnable(() ->
                                            conversationPersistenceService.appendAssistantMessage(session, acknowledgement, true))
                                    .subscribeOn(Schedulers.boundedElastic());
                            return Flux.concat(
                                    Flux.just(imageJobEvent(job), dataEvent(acknowledgement)),
                                    saveAssistant.thenMany(Flux.just(stopEvent())));
                        })))
                .onErrorResume(err -> {
                    log.error("[chat] 图片任务创建失败 cid={} err={}", conversationId, err.toString());
                    return Mono.<Void>fromRunnable(() ->
                                    conversationPersistenceService.appendAssistantMessage(session,
                                            "图片任务创建失败：" + errorMessage(err), false))
                            .subscribeOn(Schedulers.boundedElastic())
                            .onErrorResume(persistError -> Mono.empty())
                            .thenMany(Flux.just(errorEvent("图片任务创建失败：" + errorMessage(err)), stopEvent()));
                });
    }

    private Flux<ChatEventVO> chatWithMultimodal(String question, ConversationSession session,
                                                 UserApiKeyService.UserApiKeys keys,
                                                 List<ChatAttachmentRequest> attachments,
                                                 String pendingText) {
        String promptText = StringUtils.isNotBlank(question) ? question : pendingText;
        if (StringUtils.isBlank(promptText)) promptText = "请根据已上传的图片完成任务。";
        final String userText = promptText;
        return Mono.fromCallable(() -> attachments.stream().map(attachment -> mediaFor(session.identity(), attachment)).toList())
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(media -> streamChat(userText, session, keys, media,
                        StringUtils.isNotBlank(pendingText) ? pendingText + "\n[已附加图片]" : userText));
    }

    private Flux<ChatEventVO> chatWithKeys(String question, ConversationSession session,
                                           UserApiKeyService.UserApiKeys keys) {
        return streamChat(question, session, keys, List.of(), question);
    }

    private Flux<ChatEventVO> streamChat(String question, ConversationSession session,
                                         UserApiKeyService.UserApiKeys keys, List<Media> media,
                                         String persistedQuestion) {
        String conversationId = session.conversationId();
        ChatClient chatClient = chatClientFactory.create(keys, !media.isEmpty());
        String memoryKey = ConversationKeys.memoryKey(session.identity(), conversationId);
        StringBuilder assistantText = new StringBuilder();

        Flux<ChatEventVO> contentEvents = Flux.defer(() -> {
                    var request = chatClient.prompt()
                            .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, memoryKey));
                    if (media.isEmpty()) {
                        request.user(question);
                    } else {
                        request.messages(UserMessage.builder().text(question).media(media).build());
                    }
                    return request.stream().chatResponse();
                })
                        .mapNotNull(ChatService::textOf)
                        .filter(StringUtils::isNotEmpty)
                        .doOnNext(assistantText::append)
                        .map(ChatService::dataEvent);

        Mono<Void> saveUserMessage = Mono.<Void>fromRunnable(() ->
                        conversationPersistenceService.appendUserMessage(session, persistedQuestion))
                .subscribeOn(Schedulers.boundedElastic());
        Mono<Void> saveAssistantMessage = Mono.<Void>fromRunnable(() ->
                        conversationPersistenceService.appendAssistantMessage(session, assistantText.toString(), true))
                .subscribeOn(Schedulers.boundedElastic());

        return Flux.concat(
                        Flux.just(sessionInfoEvent(conversationId, session.created())),
                        saveUserMessage.thenMany(contentEvents),
                        saveAssistantMessage.thenMany(Flux.just(stopEvent())))
                .onErrorResume(err -> {
                    log.error("[chat] 对话失败 cid={} err={}", conversationId, err.toString());
                    return Mono.<Void>fromRunnable(() ->
                                    conversationPersistenceService.appendAssistantMessage(session, assistantText.toString(), false))
                            .subscribeOn(Schedulers.boundedElastic())
                            .onErrorResume(persistError -> {
                                log.error("[chat] 保存失败 cid={} err={}", conversationId, persistError.toString());
                                return Mono.empty();
                            })
                            .thenMany(Flux.just(errorEvent("对话失败: " + errorMessage(err)), stopEvent()));
                });
    }


    /** 校验问题：空 → 提示必填；超长 → 提示上限。合法返回 {@code null}。 */
    public static String validateQuestion(String question) {
        return validateQuestion(question, false);
    }

    public static String validateQuestion(String question, boolean hasAttachments) {
        if (StringUtils.isBlank(question) && !hasAttachments) {
            return "问题不能为空";
        }
        if (question != null && question.length() > MAX_QUESTION_LENGTH) {
            return "问题过长（上限 " + MAX_QUESTION_LENGTH + " 字符）";
        }
        return null;
    }

    private Media mediaFor(ChatIdentity identity, ChatAttachmentRequest attachment) {
        ImageAssetService.AssetRecord asset = imageAssets.requireReady(identity, attachment.getAssetId());
        String mime = StringUtils.isBlank(asset.mimeType()) ? "image/jpeg" : asset.mimeType();
        return new Media(MimeTypeUtils.parseMimeType(mime),
                java.net.URI.create(storage.signedGetUrl(asset.objectKey())));
    }

    private PendingContext pendingContext(String memoryKey) {
        List<Message> messages = chatMemory.get(memoryKey);
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            Object pendingFlag = message.getMetadata().get("pending");
            if (!(message instanceof UserMessage) || !Boolean.TRUE.equals(pendingFlag)
                    && !"true".equalsIgnoreCase(String.valueOf(pendingFlag))) {
                continue;
            }
            String kind = String.valueOf(message.getMetadata().getOrDefault("pendingKind", "text"));
            if ("image".equals(kind)) {
                ChatAttachmentRequest attachment = new ChatAttachmentRequest();
                attachment.setAssetId(String.valueOf(message.getMetadata().get("assetId")));
                attachment.setMimeType((String) message.getMetadata().get("mimeType"));
                attachment.setFileName((String) message.getMetadata().get("fileName"));
                Object fileSize = message.getMetadata().get("fileSize");
                if (fileSize instanceof Number number) attachment.setFileSize(number.longValue());
                return new PendingContext(null, attachment);
            }
            return new PendingContext(message.getText(), null);
        }
        return PendingContext.empty();
    }

    private static Map<String, Object> pendingMetadata(ChatAttachmentRequest attachment) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("pending", true);
        metadata.put("pendingKind", "image");
        if (attachment.getAssetId() != null) metadata.put("assetId", attachment.getAssetId());
        if (attachment.getMimeType() != null) metadata.put("mimeType", attachment.getMimeType());
        if (attachment.getFileName() != null) metadata.put("fileName", attachment.getFileName());
        if (attachment.getFileSize() != null) metadata.put("fileSize", attachment.getFileSize());
        return metadata;
    }

    private static boolean isImageGenerationRequest(String question) {
        String text = question == null ? "" : question.toLowerCase();
        return text.matches(".*(生成|创作|二次|重绘|改图|改成|换成|海报|纸刊|拼贴|蒸馏|风格化|图片编辑|image generation|generate|edit).*");
    }

    private static boolean isLikelyImageInstruction(String question) {
        String text = question == null ? "" : question.toLowerCase();
        return text.matches(".*(照片|图片|图像|上传|原图|画面|主体|保留|风格|构图|色彩|根据这|参考图|生成|创作|二次|重绘|改图|改成|换成|海报|纸刊|拼贴|蒸馏|image|photo|picture).*");
    }

    private record PendingContext(String text, ChatAttachmentRequest attachment) {
        private static PendingContext empty() { return new PendingContext(null, null); }
        private boolean hasImage() { return attachment != null && StringUtils.isNotBlank(attachment.getAssetId()); }
    }

    /** 取异常可读信息：优先 message，为空则退回异常类名。 */
    private static String errorMessage(Throwable err) {
        String message = err == null ? null : err.getMessage();
        return StringUtils.isBlank(message) ? StringUtils.toString(err) : message;
    }

    /** 从响应中取出模型输出的文本增量（无结果/无输出时返回 {@code null}）。 */
    private static String textOf(ChatResponse response) {
        if (response == null) {
            return null;
        }
        Generation generation = response.getResult();
        if (generation == null || generation.getOutput() == null) {
            return null;
        }
        return generation.getOutput().getText();
    }

    /** 构造正文增量事件。 */
    private static ChatEventVO dataEvent(String text) {
        return ChatEventVO.builder()
                .eventType(ChatEventTypeEnum.DATA.getValue())
                .eventData(text)
                .build();
    }

    private static ChatEventVO imageJobEvent(ImageJobService.JobView job) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("jobId", job.jobId());
        data.put("status", job.status());
        data.put("mode", job.mode());
        data.put("createdAt", job.createdAt());
        return ChatEventVO.builder()
                .eventType(ChatEventTypeEnum.IMAGE_JOB.getValue())
                .eventData(data)
                .build();
    }

    /** 构造结束事件（流末尾必发，无数据）。 */
    private static ChatEventVO stopEvent() {
        return ChatEventVO.builder().eventType(ChatEventTypeEnum.STOP.getValue()).build();
    }

    /** 构造错误事件（仍以 200 的 SSE 通道下发，由前端按 eventType 处理）。 */
    private static ChatEventVO errorEvent(String message) {
        return ChatEventVO.builder()
                .eventType(ChatEventTypeEnum.ERROR.getValue())
                .eventData(message)
                .build();
    }

    /** 构造会话信息事件：下发给前端会话 id 与起始时间戳。 */
    private static ChatEventVO sessionInfoEvent(String conversationId, boolean created) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("conversationId", conversationId);
        data.put("created", created);
        data.put("timestamp", System.currentTimeMillis());
        return ChatEventVO.builder()
                .eventType(ChatEventTypeEnum.SESSION_INFO.getValue())
                .eventData(data)
                .build();
    }
}
