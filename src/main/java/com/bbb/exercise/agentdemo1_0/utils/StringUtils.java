package com.bbb.exercise.agentdemo1_0.utils;

import cn.hutool.core.util.StrUtil;

/**
 * 字符串工具类，继承了 hutool 的 {@link StrUtil}，
 * 提供 isBlank / isNotBlank / isEmpty / trim 等常用方法。
 */
public class StringUtils extends StrUtil {

    /** null 转为空字符串，否则返回原对象的字符串表示（用于替代手写 nullToEmpty） */
    public static String toString(Object obj) {
        return obj == null ? "" : obj.toString();
    }
}
