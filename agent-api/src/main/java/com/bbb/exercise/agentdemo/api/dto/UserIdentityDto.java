package com.bbb.exercise.agentdemo.api.dto;

public record UserIdentityDto(String userId, String tenantId, boolean authenticated) {
}
