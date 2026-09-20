package com.bbb.exercise.agentdemo1_0.tools.weather;

import com.bbb.exercise.agentdemo1_0.tools.TavilySearcher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;


/**
 * 兜底天气数据源：Tavily 联网搜索。
 * 当首选 {@link OpenMeteoWeatherProvider} 失败时由 {@link WeatherService} 降级到这里。
 */
@Slf4j
@Component
@Order(2)
public class TavilyWeatherProvider implements WeatherProvider {

    /** 搜索式查询模板：用自然语言让搜索服务返回天气信息 */
    private static final String QUERY_TEMPLATE = "查询 %s 今天的实时天气，包含温度、天气状况、湿度、风速";

    private final TavilySearcher tavilySearcher;

    public TavilyWeatherProvider(TavilySearcher tavilySearcher) {
        this.tavilySearcher = tavilySearcher;
    }

    @Override
    public String name() {
        return "Tavily";
    }

    @Override
    public String fetchWeather(String city) {
        return tavilySearcher.search("weather:" + city, String.format(QUERY_TEMPLATE, city));
    }
}
