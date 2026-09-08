package com.bbb.exercise.agentdemo1_0.utils;

import cn.hutool.core.util.StrUtil;

/**
 * 字符串工具类，继承了 hutool 的 {@link StrUtil}，
 * 提供 isBlank / isNotBlank / isEmpty / trim / toString 等常用方法。
 */
public class StringUtils extends StrUtil {

    /** null 转为空字符串，否则返回原对象的字符串表示（用于替代手写 nullToEmpty） */
    public static String toString(Object obj) {
        return obj == null ? "" : obj.toString();
    }

    /**
     * 截断字符串：超过 {@code max} 长度时截断并追加省略提示，供日志与直播事件展示使用。
     *
     * @param s   原字符串
     * @param max 最大保留长度
     * @return 截断后的字符串；{@code null} 返回空串
     */
    public static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…(已截断)";
    }
}
