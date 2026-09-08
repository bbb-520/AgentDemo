package com.bbb.exercise.agentdemo1_0.utils;

import cn.hutool.core.util.ObjectUtil;

import java.lang.reflect.Field;
import java.math.BigDecimal;

/**
 * Object 操作工具
 */
public class ObjectUtils extends ObjectUtil {

    /** 为 object 中的基本类型字段设置默认值（对 null 字段不操作） */
    public static void setDefault(Object target) {
        if (target == null) {
            return;
        }
        Class<?> clazz = target.getClass();
        Field[] declaredFields = clazz.getDeclaredFields();
        for (Field field : declaredFields) {
            setDefault(field, target);
        }
    }

    private static void setDefault(Field field, Object target) {
        field.setAccessible(true);
        try {
            Object value = field.get(target);
            if (value != null) {
                return;
            }
            String type = field.getGenericType().toString();
            Object defaultValue;
            switch (type) {
                case "class java.lang.String":
                case "class java.lang.Character":
                    defaultValue = "";
                    break;
                case "class java.lang.Double":
                    defaultValue = 0.0d;
                    break;
                case "class java.lang.Long":
                    defaultValue = 0L;
                    break;
                case "class java.lang.Short":
                    defaultValue = (short) 0;
                    break;
                case "class java.lang.Integer":
                    defaultValue = 0;
                    break;
                case "class java.lang.Float":
                    defaultValue = 0f;
                    break;
                case "class java.lang.Byte":
                    defaultValue = (byte) 0;
                    break;
                case "class java.math.BigDecimal":
                    defaultValue = BigDecimal.ZERO;
                    break;
                case "class java.lang.Boolean":
                    defaultValue = Boolean.FALSE;
                    break;
                default:
                    defaultValue = null;
            }
            field.set(target, defaultValue);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
