package com.bbb.exercise.agentdemo.common.trace;

import java.util.UUID;

public final class RequestId {
    public static final String HEADER = "X-Request-Id";

    private RequestId() {
    }

    public static String resolve(String candidate) {
        if (candidate != null && !candidate.isBlank() && candidate.length() <= 128) {
            return candidate.trim();
        }
        return UUID.randomUUID().toString();
    }
}
