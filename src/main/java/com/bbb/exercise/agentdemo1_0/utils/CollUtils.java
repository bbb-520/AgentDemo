package com.bbb.exercise.agentdemo1_0.utils;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.collection.IterUtil;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 继承自 hutool 的集合工具类
 */
public class CollUtils extends CollectionUtil {

    public static <T> List<T> emptyList() {
        return Collections.emptyList();
    }

    public static <T> Set<T> emptySet() {
        return Collections.emptySet();
    }

    public static <K, V> Map<K, V> emptyMap() {
        return Collections.emptyMap();
    }

    public static <T> Set<T> singletonSet(T t) {
        return Collections.singleton(t);
    }

    public static <T> List<T> singletonList(T t) {
        return Collections.singletonList(t);
    }

    public static List<Integer> convertToInteger(List<String> originList) {
        return CollUtils.isNotEmpty(originList)
                ? originList.stream().map(NumberUtils::parseInt).collect(Collectors.toList())
                : null;
    }

    public static List<Long> convertToLong(List<String> originList) {
        return CollUtils.isNotEmpty(originList)
                ? originList.stream().map(NumberUtils::parseLong).collect(Collectors.toList())
                : null;
    }

    /** 以 conjunction 为分隔符将集合转换为字符串 */
    public static <T> String join(Collection<T> collection, CharSequence conjunction) {
        if (null == collection || collection.isEmpty()) {
            return null;
        }
        return IterUtil.join(collection.iterator(), conjunction);
    }

    public static <T> String joinIgnoreNull(Collection<T> collection, CharSequence conjunction) {
        if (null == collection || collection.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (T t : collection) {
            if (t == null) {
                continue;
            }
            sb.append(t).append(conjunction);
        }
        if (sb.length() <= 0) {
            return null;
        }
        return sb.delete(sb.length() - conjunction.length(), sb.length()).toString();
    }

    /** 将元素加入集合，过滤掉 null */
    public static <T> void add(Collection<T> list, T... data) {
        if (list == null || ArrayUtils.isEmpty(data)) {
            return;
        }
        for (T t : data) {
            if (ObjectUtils.isNotEmpty(t)) {
                list.add(t);
            }
        }
    }

    /** 将两个 Map 中相同 key 的次数相加 */
    public static Map<Long, Integer> union(Map<Long, Integer> map1, Map<Long, Integer> map2) {
        if (CollUtils.isEmpty(map1)) {
            return map2;
        } else if (CollUtils.isEmpty(map2)) {
            return map1;
        }
        for (Map.Entry<Long, Integer> entry : map1.entrySet()) {
            Integer num = map2.get(entry.getKey());
            map2.put(entry.getKey(), NumberUtils.null2Zero(num) + entry.getValue());
        }
        return map2;
    }

    public static <T, R> R getFiledOfFirst(List<T> list, Function<T, R> function) {
        if (CollUtils.isEmpty(list)) {
            return null;
        }
        return function.apply(list.get(0));
    }
}
