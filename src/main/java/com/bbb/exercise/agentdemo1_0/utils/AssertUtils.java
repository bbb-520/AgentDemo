package com.bbb.exercise.agentdemo1_0.utils;

import org.springframework.util.StringUtils;

/**
 * 断言工具类：校验失败时抛出 {@link IllegalArgumentException}，
 * 与本项目的参数校验风格保持一致。
 */
public class AssertUtils {

    public static void isNotBlank(String str, String... message) {
        if (!StringUtils.hasText(str)) {
            handleException(message);
        }
    }

    private static void handleException(String... message) {
        String msg = "请求参数不合法";
        if (message != null && message.length > 0) {
            msg = message[0];
        }
        throw new IllegalArgumentException(msg);
    }
}
