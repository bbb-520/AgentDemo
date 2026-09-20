package com.bbb.exercise.agentdemo1_0.tools.weather;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.bbb.exercise.agentdemo1_0.utils.AssertUtils;
import com.bbb.exercise.agentdemo1_0.utils.StringUtils;


/**
 * 天气查询工具（暴露给模型的 {@code getWeather}）。
 *
 * <p>只做参数校验与清洗，真正的多数据源查询与降级交给 {@link WeatherService}。
 * 模型会根据系统提示词在需要天气时自动调用本方法。
 */
@Slf4j
@Component
public class WeatherTool {

    private final WeatherService weatherService;

    public WeatherTool(WeatherService weatherService) {
        this.weatherService = weatherService;
    }

    /**
     * 查询指定城市的实时天气
     *
     * @param city 城市名称
     * @return 天气信息文本
     */
    @Tool(description = "查询指定城市的实时天气，返回温度、天气状况、湿度、风速等信息")
    public String getWeather(@ToolParam(description = "要查询天气的城市名称，例如 北京") String city) {
        AssertUtils.isNotBlank(city, "城市名称不能为空");
        return weatherService.getWeather(StringUtils.trim(city));
    }
}
