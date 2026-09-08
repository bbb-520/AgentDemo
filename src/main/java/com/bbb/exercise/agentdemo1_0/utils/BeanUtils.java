package com.bbb.exercise.agentdemo1_0.utils;

import cn.hutool.core.bean.BeanUtil;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 继承自 hutool 的 BeanUtil，增加了 bean 转换时自定义转换器的功能
 */
public class BeanUtils extends BeanUtil {

    /** 将原对象转换成目标对象，字段不匹配时可用转换器处理 */
    public static <R, T> T copyBean(R source, Class<T> clazz, Convert<R, T> convert) {
        T target = copyBean(source, clazz);
        if (convert != null) {
            convert.convert(source, target);
        }
        return target;
    }

    public static <R, T> T copyBean(R source, Class<T> clazz) {
        if (source == null) {
            return null;
        }
        return toBean(source, clazz);
    }

    public static <R, T> List<T> copyList(List<R> list, Class<T> clazz) {
        if (list == null || list.size() == 0) {
            return CollUtils.emptyList();
        }
        return copyToList(list, clazz);
    }

    public static <R, T> List<T> copyList(List<R> list, Class<T> clazz, Convert<R, T> convert) {
        if (list == null || list.size() == 0) {
            return CollUtils.emptyList();
        }
        return list.stream().map(r -> copyBean(r, clazz, convert)).collect(Collectors.toList());
    }
}
