package com.bbb.exercise.agentdemo1_0.utils;

import java.util.Map;

/**
 * 断言工具类：校验失败时抛出 {@link IllegalArgumentException}，
 * 与本项目的参数校验风格保持一致。若需映射到统一业务异常，可替换为项目异常类型。
 */
public class AssertUtils {

    public static void equals(Object obj1, Object obj2, String... message) {
        if (obj1 == null || obj2 == null) {
            handleException(message);
            return;
        }
        if (obj1 == obj2) {
            return;
        }
        if (!obj1.equals(obj2)) {
            handleException(message);
        }
    }

    public static void isNotNull(Object obj, String... message) {
        if (obj == null) {
            handleException(message);
        }
    }

    public static void isNotBlank(String str, String... message) {
        if (StringUtils.isBlank(str)) {
            handleException(message);
        }
    }

    public static void isTrue(Boolean boo, String... message) {
        if (boo == null || !boo) {
            handleException(message);
        }
    }

    public static void isFalse(Boolean boo, String... message) {
        if (boo == null || boo) {
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

    public static void isNotEmpty(Iterable<?> coll, String... message) {
        if (CollUtils.isEmpty(coll)) {
            handleException(message);
        }
    }

    public static void isNotEmpty(Map<?, ?> map, String... message) {
        if (CollUtils.isEmpty(map)) {
            handleException(message);
        }
    }
}
