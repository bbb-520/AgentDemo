package com.bbb.exercise.agentdemo.orchestrator.domain;

import java.time.Instant;
import java.util.UUID;

public record AgentRun(UUID id, String workflow, String userId, String input,
                       RunStatus status, Instant createdAt, Instant updatedAt) {
    public AgentRun advance(RunStatus next) {
        return new AgentRun(id, workflow, userId, input, next, createdAt, Instant.now());
    }

    public enum RunStatus {
        DRAFT, REVIEWING, REVISING, VALIDATING, APPROVED, REJECTED
    }
}
