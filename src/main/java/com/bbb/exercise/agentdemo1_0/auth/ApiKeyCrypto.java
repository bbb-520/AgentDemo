package com.bbb.exercise.agentdemo1_0.auth;

import org.springframework.stereotype.Component;

/**
 * API Key compatibility adapter.
 *
 * <p>当前按本地开发要求明文保存 API Key，不再依赖任何加密配置。
 * 类名和方法名暂时保留，避免影响已有服务注入关系。</p>
 */
@Component
public class ApiKeyCrypto {

    public String encrypt(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public String decrypt(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
