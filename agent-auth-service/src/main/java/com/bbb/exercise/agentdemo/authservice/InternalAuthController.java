package com.bbb.exercise.agentdemo.authservice;

import com.bbb.exercise.agentdemo.api.dto.UserIdentityDto;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/auth")
public class InternalAuthController {
    @GetMapping("/users/{userId}")
    public UserIdentityDto getUser(@PathVariable String userId,
                                   @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        return new UserIdentityDto(userId, tenantId, true);
    }
}
