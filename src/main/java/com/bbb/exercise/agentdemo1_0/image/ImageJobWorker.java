package com.bbb.exercise.agentdemo1_0.image;

import com.bbb.exercise.agentdemo1_0.oss.OssStorageService;
import com.bbb.exercise.agentdemo1_0.auth.UserApiKeyService;
import com.bbb.exercise.agentdemo1_0.identity.ChatIdentity;
import com.bbb.exercise.agentdemo1_0.zine.ZineGenerationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** 单实例 MVP Worker：从 MySQL 队列领取任务，生成完成后把结果保存回私有 OSS。 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.image-jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class ImageJobWorker {

    private final ImageJobService jobs;
    private final OssStorageService storage;
    private final ZineGenerationService generation;
    private final UserApiKeyService userKeys;

    @Value("${app.image-jobs.max-attempts:2}")
    private int maxAttempts;

    @Scheduled(fixedDelayString = "${app.image-jobs.poll-interval:3000ms}", initialDelay = 5000)
    public void poll() {
        // OSS 未配置时不领取任务，避免生成结果无法保存。
        if (!storage.isConfigured()) return;
        ImageJobService.JobRecord job = jobs.claimNext();
        if (job == null) return;
        try {
            String apiKey = userKeys.get(new ChatIdentity(job.tenantId(), job.userId(), true)).qwenApiKey();
            String sourceUrl = storage.signedGetUrl(job.sourceObjectKey());
            ZineGenerationService.ProviderGeneration generated = generation
                    .generateFromSourceUrl(sourceUrl, job.mode(), job.language(), job.prompt(), apiKey)
                    .block(Duration.ofMinutes(4));
            if (generated == null || generated.result() == null || generated.result().imageUrl() == null) {
                throw new IllegalStateException("图片模型没有返回结果");
            }
            storage.copyRemoteImageToObject(generated.result().imageUrl(), job.outputObjectKey());
            jobs.succeed(job.id(), job.outputObjectKey(), generated.rationale(), generated.result().providerRequestId());
            log.info("[image-job] completed jobId={}", job.id());
        } catch (Exception error) {
            log.warn("[image-job] failed jobId={} message={}", job.id(), error.getMessage());
            jobs.fail(job.id(), error.getMessage(), maxAttempts);
        }
    }
}
