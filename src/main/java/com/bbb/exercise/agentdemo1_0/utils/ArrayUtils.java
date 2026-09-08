package com.bbb.exercise.agentdemo1_0.utils;

import cn.hutool.core.util.ArrayUtil;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 数组工具类，继承 hutool 的 {@link ArrayUtil}
 */
public class ArrayUtils extends ArrayUtil {

    /** 将源数组转换成指定类型的列表 */
    public static <R, T> List<T> convert(R[] originList, Class<T> targetClazz) {
        return convert(originList, targetClazz, null);
    }

    /** 将源数组转换成指定类型的列表，可传入自定义转换器处理特殊字段 */
    public static <R, T> List<T> convert(R[] originList, Class<T> targetClazz, Convert<R, T> convert) {
        if (isEmpty(originList)) {
            return null;
        }
        return Arrays.stream(originList)
                .map(origin -> BeanUtils.copyBean(origin, targetClazz, convert))
                .collect(Collectors.toList());
    }
}
