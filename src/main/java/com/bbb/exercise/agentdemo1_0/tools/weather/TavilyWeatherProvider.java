package com.bbb.exercise.agentdemo1_0.tools.weather;

import com.bbb.exercise.agentdemo1_0.tools.TavilySearcher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Tavily 搜索天气数据源（兜底渠道）。
 *
 * <p>当首选渠道（Open-Meteo）网络不可达、限流或解析失败时，
 * 由 {@link WeatherService} 自动降级到本实现，保证天气能力始终可用。
 */
@Slf4j
@Component
@Order(2)
public class TavilyWeatherProvider implements WeatherProvider {

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
