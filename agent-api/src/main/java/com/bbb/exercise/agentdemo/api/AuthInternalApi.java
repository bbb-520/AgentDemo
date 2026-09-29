package com.bbb.exercise.agentdemo.api;

import com.bbb.exercise.agentdemo.api.dto.UserIdentityDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "agent-auth-service", path = "/internal/auth")
public interface AuthInternalApi {
    @GetMapping("/users/{userId}")
    UserIdentityDto getUser(@PathVariable("userId") String userId);
}
