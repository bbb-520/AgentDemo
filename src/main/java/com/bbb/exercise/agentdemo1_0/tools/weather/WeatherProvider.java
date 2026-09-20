package com.bbb.exercise.agentdemo1_0.tools.weather;

/**
 * 天气数据源抽象。实现类用 {@code @Order} 声明优先级，由 {@link WeatherService} 依次降级调用。
 */
public interface WeatherProvider {


    /**
     * 查询指定城市的天气。
     *
     * @param city 城市名称
     * @return 面向模型展示的天气描述文本
     * @throws RuntimeException 该数据源不可用时抛出，交由 {@link WeatherService} 降级
     */
    String fetchWeather(String city);

    /** 渠道名称，仅用于日志与文档 */
    String name();
}
