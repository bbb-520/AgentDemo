package com.bbb.exercise.agentdemo.orchestrator.service;

import com.bbb.exercise.agentdemo.orchestrator.domain.AgentRun;
import com.bbb.exercise.agentdemo.orchestrator.domain.AgentRun.RunStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AgentWorkflowService {
    private final ConcurrentHashMap<UUID, AgentRun> runs = new ConcurrentHashMap<>();

    public AgentRun start(String workflow, String input, String userId) {
        if (!"REVISE".equalsIgnoreCase(workflow)) {
            throw new IllegalArgumentException("不支持的工作流: " + workflow);
        }
        AgentRun run = new AgentRun(UUID.randomUUID(), "REVISE", userId, input,
                RunStatus.DRAFT, Instant.now(), Instant.now());
        runs.put(run.id(), run);
        return run;
    }

    public AgentRun advance(UUID id, RunStatus next) {
        return runs.compute(id, (key, current) -> {
            if (current == null) throw new IllegalArgumentException("AgentRun 不存在: " + id);
            if (!allowed(current.status(), next)) {
                throw new IllegalStateException("非法状态转移: " + current.status() + " -> " + next);
            }
            return current.advance(next);
        });
    }

    public AgentRun get(UUID id) {
        return runs.get(id);
    }

    private boolean allowed(RunStatus current, RunStatus next) {
        return switch (current) {
            case DRAFT -> next == RunStatus.REVIEWING;
            case REVIEWING -> next == RunStatus.REVISING || next == RunStatus.APPROVED;
            case REVISING -> next == RunStatus.VALIDATING;
            case VALIDATING -> next == RunStatus.APPROVED || next == RunStatus.REJECTED;
            case APPROVED, REJECTED -> false;
        };
    }
}
