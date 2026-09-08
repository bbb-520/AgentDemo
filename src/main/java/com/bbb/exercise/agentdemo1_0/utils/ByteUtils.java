package com.bbb.exercise.agentdemo1_0.utils;

import cn.hutool.core.util.ByteUtil;

/**
 * 字节工具类，继承 hutool 的 {@link ByteUtil}
 */
public class ByteUtils extends ByteUtil {

    /** 将 byte[] 数组转换成字符串，为空返回 "" */
    public static String parse(byte[] content) {
        if (content == null || content.length <= 0) {
            return StringUtils.EMPTY;
        }
        return new String(content);
    }
}
