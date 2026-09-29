package com.bbb.exercise.agentdemo.orchestrator.web;

import com.bbb.exercise.agentdemo.common.api.ApiResponse;
import com.bbb.exercise.agentdemo.orchestrator.domain.AgentRun;
import com.bbb.exercise.agentdemo.orchestrator.domain.AgentRun.RunStatus;
import com.bbb.exercise.agentdemo.orchestrator.service.AgentWorkflowService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/api/agent-runs")
public class AgentWorkflowController {
    private final AgentWorkflowService workflows;

    public AgentWorkflowController(AgentWorkflowService workflows) {
        this.workflows = workflows;
    }

    @PostMapping
    public ApiResponse<AgentRun> start(@RequestParam(name = "workflow", defaultValue = "REVISE") String workflow,
                                       @RequestParam(name = "input") String input,
                                       @RequestHeader(value = "X-User-Id", defaultValue = "anonymous") String userId) {
        return ApiResponse.success(workflows.start(workflow, input, userId));
    }

    @PostMapping("/{id}/advance")
    public ApiResponse<AgentRun> advance(@PathVariable UUID id, @RequestParam(name = "status") RunStatus status) {
        return ApiResponse.success(workflows.advance(id, status));
    }

    @GetMapping("/{id}")
    public ApiResponse<AgentRun> get(@PathVariable UUID id) {
        AgentRun run = workflows.get(id);
        if (run == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "AgentRun 不存在");
        return ApiResponse.success(run);
    }
}
