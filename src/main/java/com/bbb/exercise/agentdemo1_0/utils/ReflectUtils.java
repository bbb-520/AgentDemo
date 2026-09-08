package com.bbb.exercise.agentdemo1_0.utils;

import cn.hutool.core.util.ReflectUtil;

/**
 * 反射工具
 */
public class ReflectUtils extends ReflectUtil {

    /** 判断一个类中是否含有指定字段 */
    public static boolean containField(String fieldName, Class<?> clazz) {
        return getField(clazz, fieldName) != null;
    }
}
