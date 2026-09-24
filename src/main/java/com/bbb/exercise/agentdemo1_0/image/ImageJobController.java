package com.bbb.exercise.agentdemo1_0.image;

import com.bbb.exercise.agentdemo1_0.identity.ChatIdentityResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;

/** 图片生成任务状态、会话任务与作品档案接口。 */
@RestController
@RequestMapping("/api/image-jobs")
@RequiredArgsConstructor
public class ImageJobController {
    private final ChatIdentityResolver identityResolver;
    private final ImageJobService jobs;

    @GetMapping("/{jobId}")
    public Mono<ResponseEntity<ImageJobService.JobView>> get(@PathVariable String jobId, ServerWebExchange exchange) {
        return identityResolver.resolveRequired(exchange).map(identity -> ResponseEntity.ok(jobs.get(identity, jobId)));
    }

    @GetMapping("/{jobId}/download")
    public Mono<ResponseEntity<Void>> download(@PathVariable String jobId, ServerWebExchange exchange) {
        return identityResolver.resolveRequired(exchange).map(identity -> ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(jobs.downloadUrl(identity, jobId))).<Void>build());
    }

    @GetMapping
    public Mono<ResponseEntity<java.util.List<ImageJobService.JobView>>> conversation(
            @RequestParam String conversationId, ServerWebExchange exchange) {
        return identityResolver.resolveRequired(exchange)
                .map(identity -> ResponseEntity.ok(jobs.conversation(identity, conversationId)));
    }

    @GetMapping("/archive")
    public Mono<ResponseEntity<java.util.List<ImageJobService.JobView>>> archive(
            @RequestParam(defaultValue = "24") int limit, ServerWebExchange exchange) {
        return identityResolver.resolveRequired(exchange)
                .map(identity -> ResponseEntity.ok(jobs.archive(identity, limit)));
    }
}
