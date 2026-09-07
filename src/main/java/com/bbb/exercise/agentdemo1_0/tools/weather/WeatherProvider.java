package com.bbb.exercise.agentdemo1_0.tools.weather;

/**
 * 天气数据源抽象：一个实现 = 一个可用的天气数据渠道。
 *
 * <p><b>扩展方式</b>：新增数据源时，只需新建一个 {@code @Component} 实现本接口，
 * 并用 {@code @Order} 声明优先级（数值越小越优先），{@link WeatherService}
 * 会按顺序尝试并在失败时自动降级，无需改动任何既有代码。
 */
public interface WeatherProvider {

    /**
     * 查询指定城市的实时天气。
     *
     * @param city 城市名称（已 trim，非空）
     * @return 面向模型的天气描述文本
     * @throws RuntimeException 查询失败（网络 / 解析 / 无结果），由 WeatherService 降级到下一个渠道
     */
    String fetchWeather(String city);

    /** 渠道名称，仅用于日志与文档 */
    String name();
}
