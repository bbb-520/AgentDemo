package com.bbb.exercise.agentdemo.api;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "agent-media-service", path = "/internal/media")
public interface MediaInternalApi {
    @GetMapping("/assets/{assetId}/ownership")
    boolean ownsAsset(@PathVariable("assetId") String assetId);
}
