package com.bbb.exercise.agentdemo1_0.image;

import com.bbb.exercise.agentdemo1_0.config.OssProperties;
import com.bbb.exercise.agentdemo1_0.auth.UserApiKeyService;
import com.bbb.exercise.agentdemo1_0.dto.ChatAttachmentRequest;
import com.bbb.exercise.agentdemo1_0.identity.ChatIdentity;
import com.bbb.exercise.agentdemo1_0.oss.OssStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** 图片生成任务的持久化、所有权校验和状态转换。 */
@Service
@RequiredArgsConstructor
public class ImageJobService {

    private final JdbcTemplate jdbc;
    private final ImageAssetService assets;
    private final OssProperties ossProperties;
    private final OssStorageService storage;
    private final UserApiKeyService userKeys;

    public JobView create(ChatIdentity identity, String conversationId, String question,
                          List<ChatAttachmentRequest> attachments) {
        if (attachments == null || attachments.isEmpty()) throw new IllegalArgumentException("请先上传一张照片");
        if (attachments.size() > 1) throw new IllegalArgumentException("当前一次只支持一张照片");
        if (!userKeys.get(identity).hasQwen()) throw new IllegalStateException("请先在用户页配置阿里云 API Key");
        ChatAttachmentRequest attachment = attachments.get(0);
        ImageAssetService.AssetRecord source = assets.requireReady(identity, attachment.getAssetId());
        String jobId = UUID.randomUUID().toString();
        String ownerPart = source.objectKey().split("/").length > 1 ? source.objectKey().split("/")[1] : "private";
        String outputKey = ossProperties.getOutputPrefix() + "/" + ownerPart + "/"
                + LocalDateTime.now().toLocalDate() + "/" + jobId + ".png";
        String prompt = question == null || question.isBlank() ? "请根据这张照片进行一次有创意的二次生成。" : question.trim();
        String mode = inferMode(prompt);
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("INSERT INTO image_job(id,tenant_id,user_id,conversation_id,source_asset_id,source_object_key,output_object_key,mode,language,prompt,status,created_at,expires_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,'QUEUED',?,?)",
                jobId, identity.tenantId(), identity.userId(), conversationId, source.id(), source.objectKey(),
                outputKey, mode, "chinese", prompt, now, now.plusDays(30));
        return view(identity, jobId);
    }

    public JobRecord claimNext() {
        List<JobRecord> jobs = jdbc.query("SELECT * FROM image_job WHERE status='QUEUED' AND expires_at>? ORDER BY created_at LIMIT 1",
                (rs, rowNum) -> map(rs), LocalDateTime.now());
        if (jobs.isEmpty()) return null;
        JobRecord job = jobs.get(0);
        int updated = jdbc.update("UPDATE image_job SET status='PROCESSING',started_at=?,attempt_count=attempt_count+1 WHERE id=? AND status='QUEUED'",
                LocalDateTime.now(), job.id());
        return updated == 1 ? job : null;
    }

    public void succeed(String jobId, String outputKey, String rationale, String providerRequestId) {
        jdbc.update("UPDATE image_job SET status='SUCCEEDED',output_object_key=?,rationale=?,provider_request_id=?,completed_at=?,error_message=NULL WHERE id=?",
                outputKey, rationale, providerRequestId, LocalDateTime.now(), jobId);
    }

    public void fail(String jobId, String message, int maxAttempts) {
        String safe = message == null ? "图片生成失败" : message.replaceAll("[\\r\\n]+", " ");
        if (safe.length() > 1000) safe = safe.substring(0, 1000);
        int attempts = Math.max(1, maxAttempts);
        LocalDateTime completedAt = LocalDateTime.now();
        jdbc.update("UPDATE image_job SET status=CASE WHEN attempt_count < ? THEN 'QUEUED' ELSE 'FAILED' END,"
                        + "error_message=?,completed_at=CASE WHEN attempt_count < ? THEN NULL ELSE ? END WHERE id=?",
                attempts, safe, attempts, completedAt, jobId);
    }

    public JobView get(ChatIdentity identity, String jobId) { return view(identity, jobId); }

    /** Resolve the publishable image from a trusted, successful job owned by this identity. */
    public PublishSource requirePublishableOutput(ChatIdentity identity, String jobId) {
        JobRecord job = requireOwnedJob(identity, jobId);
        if (!"SUCCEEDED".equals(job.status()) || job.outputObjectKey() == null || job.outputObjectKey().isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "只有成功生成的图片可以发布");
        }
        return new PublishSource(job.id(), job.outputObjectKey());
    }

    public String downloadUrl(ChatIdentity identity, String jobId) {
        JobRecord job = requireOwnedJob(identity, jobId);
        if (!"SUCCEEDED".equals(job.status()) || job.outputObjectKey() == null || job.outputObjectKey().isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "只有成功生成的图片可以下载");
        }
        return storage.signedDownloadUrl(job.outputObjectKey(), "bobo-" + job.id() + ".png", Duration.ofMinutes(5));
    }

    public List<JobView> conversation(ChatIdentity identity, String conversationId) {
        return jdbc.query("SELECT * FROM image_job WHERE tenant_id=? AND user_id=? AND conversation_id=? ORDER BY created_at",
                (rs, rowNum) -> view(identity, map(rs)), identity.tenantId(), identity.userId(), conversationId);
    }

    public List<JobView> archive(ChatIdentity identity, int limit) {
        int safeLimit = Math.max(1, Math.min(50, limit));
        return jdbc.query("SELECT * FROM image_job WHERE tenant_id=? AND user_id=? AND status='SUCCEEDED' ORDER BY created_at DESC LIMIT " + safeLimit,
                (rs, rowNum) -> view(identity, map(rs)), identity.tenantId(), identity.userId());
    }

    private JobView view(ChatIdentity identity, String jobId) {
        return view(identity, requireOwnedJob(identity, jobId));
    }

    private JobRecord requireOwnedJob(ChatIdentity identity, String jobId) {
        List<JobRecord> rows = jdbc.query("SELECT * FROM image_job WHERE id=? AND tenant_id=? AND user_id=?",
                (rs, rowNum) -> map(rs), jobId, identity.tenantId(), identity.userId());
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "图片任务不存在");
        return rows.get(0);
    }

    private JobView view(ChatIdentity identity, JobRecord job) {
        String url = "SUCCEEDED".equals(job.status()) && job.outputObjectKey() != null
                ? storage.signedGetUrl(job.outputObjectKey()) : null;
        return new JobView(job.id(), job.status(), job.prompt(), job.mode(), job.createdAt(),
                job.completedAt(), url, job.rationale(), job.errorMessage());
    }

    private static JobRecord map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new JobRecord(rs.getString("id"), rs.getString("tenant_id"), rs.getString("user_id"),
                rs.getString("conversation_id"), rs.getString("source_object_key"), rs.getString("output_object_key"),
                rs.getString("mode"), rs.getString("language"), rs.getString("prompt"), rs.getString("status"),
                rs.getString("rationale"), rs.getString("provider_request_id"), rs.getString("error_message"),
                rs.getTimestamp("created_at").toLocalDateTime(), nullable(rs, "started_at"), nullable(rs, "completed_at"));
    }

    private static LocalDateTime nullable(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        java.sql.Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    private static String inferMode(String question) {
        String text = question == null ? "" : question;
        return text.matches(".*(蒸馏|提取情绪|不要保留原图|不保留照片|抽象重构).*")
                ? "distillation" : "gathered";
    }

    public record JobRecord(String id, String tenantId, String userId, String conversationId,
                            String sourceObjectKey, String outputObjectKey, String mode, String language,
                            String prompt, String status, String rationale, String providerRequestId,
                            String errorMessage, LocalDateTime createdAt, LocalDateTime startedAt,
                            LocalDateTime completedAt) {}

    public record JobView(String jobId, String status, String prompt, String mode,
                          LocalDateTime createdAt, LocalDateTime completedAt, String imageUrl,
                          String rationale, String error) {}

    public record PublishSource(String jobId, String outputObjectKey) {}
}
