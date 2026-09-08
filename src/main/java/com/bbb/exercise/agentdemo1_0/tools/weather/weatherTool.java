package com.bbb.exercise.agentdemo1_0.tools.weather;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.bbb.exercise.agentdemo1_0.utils.AssertUtils;
import com.bbb.exercise.agentdemo1_0.utils.StringUtils;

/**
 * 查询城市的实时天气。
 *
 * <p>本工具只做「参数校验 + 委托」，数据来源完全交给
 * {@link WeatherService}（责任链：Open-Meteo 结构化实况优先，
 * Tavily 搜索自动兜底）。新增/下线天气数据源时本类零改动。
 *
 * <p>工具入参使用扁平 {@code String} 而非 DTO：Spring AI 会按参数名生成
 * {@code {"city":"北京"}} 这种扁平 JSON Schema，让模型少输出 token、
 * 降低参数出错概率。
 */
@Slf4j
@Component
public class weatherTool {

    private final WeatherService weatherService;

    public weatherTool(WeatherService weatherService) {
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
