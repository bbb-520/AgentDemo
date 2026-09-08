package com.bbb.exercise.agentdemo1_0.utils;

/**
 * 对原对象进行计算，设置到目标对象中。
 * 作为 {@link BeanUtils}、{@link ArrayUtils}、{@link TreeDataUtils} 的自定义转换器契约。
 */
public interface Convert<R, T> {
    void convert(R origin, T target);
}
