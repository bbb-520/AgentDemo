package com.bbb.exercise.agentdemo.mediaservice;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/media")
public class InternalMediaController {
    @GetMapping("/assets/{assetId}/ownership")
    public boolean ownsAsset(@PathVariable String assetId,
                             @RequestHeader(value = "X-User-Id", defaultValue = "anonymous") String userId) {
        return assetId != null && !assetId.isBlank() && userId != null && !userId.isBlank();
    }
}
