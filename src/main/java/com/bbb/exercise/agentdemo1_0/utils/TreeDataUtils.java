package com.bbb.exercise.agentdemo1_0.utils;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 树形数据处理工具
 */
@Slf4j
public class TreeDataUtils {

    /** 遍历树状数据计算目标 */
    public static <T> Object ergodicTreeCalculate(List<T> data, CalculateDataProcessor<T> calculateDataProcessor) {
        if (CollUtils.isEmpty(data)) {
            return null;
        }
        Object result = null;
        for (T t : data) {
            Object tData = calculateDataProcessor.getData(t);
            List<T> childData = calculateDataProcessor.getChildData(t);
            Object currentAllData = calculateDataProcessor.calculate(tData, ergodicTreeCalculate(childData, calculateDataProcessor));
            calculateDataProcessor.setResult(t, currentAllData);
            result = calculateDataProcessor.calculate(result, currentAllData);
        }
        return result;
    }

    /** 将树状数据转化成目标类型的列表数据，并建立父子关系（递归） */
    public static <T, R> void parseTreeToList(Object parentKey, List<R> originData,
                                              ToListDataProcessor<T, R> dataProcessor, Class<T> clazz,
                                              Convert<R, T> convert, List<T> targetData, Filter<R> filter) {
        if (CollUtils.isNotEmpty(originData)) {
            for (R data : originData) {
                T target = BeanUtils.copyBean(data, clazz, convert);
                dataProcessor.setParent(target, parentKey);
                targetData.add(target);
                parseTreeToList(dataProcessor.getKey(data), dataProcessor.getChildren(data), dataProcessor, clazz, convert, targetData, filter);
            }
        }
    }

    /** 根据父子关系将原始列表转化为树型数据（默认不过滤） */
    public static <T, R> List<T> parseToTree(List<R> originData, Class<T> clazz, DataProcessor<T, R> dataProcessor) {
        return parseToTree(originData, clazz, null, dataProcessor, new DefaultFilter());
    }

    public static <T, R> List<T> parseToTree(List<R> originData, Class<T> clazz, DataProcessor<T, R> dataProcessor, Filter<R> filter) {
        return parseToTree(originData, clazz, null, dataProcessor, filter);
    }

    public static <T, R> List<T> parseToTree(List<R> originData, Class<T> clazz, Convert<R, T> convert, DataProcessor<T, R> dataProcessor) {
        return parseToTree(originData, clazz, convert, dataProcessor, new DefaultFilter());
    }

    public static <T, R> List<T> parseToTree(List<R> originData, Class<T> clazz, Convert<R, T> convert, DataProcessor<T, R> dataProcessor, Filter<R> filter) {
        if (CollUtils.isEmpty(originData)) {
            return new ArrayList<>();
        }
        Map<Object, T> resultMap = new HashMap<>();
        originData.stream().forEach(r -> {
            if (!filter.filter(r)) {
                return;
            }
            T current = BeanUtils.copyBean(r, clazz, convert);
            dataProcessor.setChild(current, new ArrayList<>());
            Object key = dataProcessor.getKey(r);
            T currentInMap = resultMap.get(key);
            if (currentInMap != null) {
                List<T> children = dataProcessor.getChild(currentInMap);
                dataProcessor.setChild(current, children);
            }

            Object parentKey = dataProcessor.getParentKey(r);
            T parent = resultMap.get(parentKey);
            if (parent == null) {
                parent = ReflectUtils.newInstance(clazz);
                dataProcessor.setChild(parent, new ArrayList<>());
            }
            List<T> children = dataProcessor.getChild(parent);
            children.add(current);
            resultMap.put(parentKey, parent);
            resultMap.put(dataProcessor.getKey(r), current);
        });
        T t = resultMap.get(dataProcessor.getRootKey());
        return t == null ? null : dataProcessor.getChild(t);
    }

    public interface DataProcessor<T, R> {
        Object getParentKey(R r);

        Object getKey(R r);

        Object getRootKey();

        List<T> getChild(T t);

        void setChild(T parent, List<T> child);
    }

    public interface ToListDataProcessor<T, R> {
        Object getKey(R r);

        void setParent(T t, Object parentKey);

        List<R> getChildren(R r);
    }

    public interface CalculateDataProcessor<T> {
        Object getData(T t);

        List<T> getChildData(T t);

        Object calculate(Object... datas);

        void setResult(T t, Object result);
    }

    public interface Filter<T> {
        default boolean filter(T t) {
            return true;
        }
    }

    public static class DefaultFilter<T> implements Filter {
    }
}
