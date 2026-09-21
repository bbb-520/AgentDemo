package com.bbb.exercise.agentdemo1_0.tools.weather;

import com.bbb.exercise.agentdemo1_0.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;


/**
 * 天气查询服务：多数据源 + 自动降级。
 *
 * <p>按注入顺序（{@code @Order} 升序）依次尝试各 {@link WeatherProvider}（当前为
 * Open-Meteo → Tavily），任一成功即返回；全部失败才抛异常。
 * 新增渠道只需实现 {@link WeatherProvider} 并标 {@code @Order}，无需改动本类。
 */
@Slf4j
@Service
public class WeatherService {

    /** Spring 自动按 @Order 升序注入 */
    private final List<WeatherProvider> providers;

    public WeatherService(List<WeatherProvider> providers) {
        this.providers = providers;
        log.info("[weather] 可用天气数据源（按优先级）: {}",
                providers.stream().map(WeatherProvider::name).toList());
    }

    /**
     * 查询指定城市的实时天气，失败自动降级到下一个数据源。
     *
     * @param city 城市名称
     * @return 天气描述文本
     * @throws IllegalStateException 所有数据源均不可用
     */
    public String getWeather(String city) {
        RuntimeException lastError = null;
        for (WeatherProvider provider : providers) {
            try {
                String result = provider.fetchWeather(city);
                if (!StringUtils.isBlank(result)) {
                    log.info("[weather] 数据源 {} 命中 city={}", provider.name(), city);
                    return StringUtils.trim(result);
                }
                log.warn("[weather] 数据源 {} 返回空，降级 city={}", provider.name(), city);
            } catch (RuntimeException e) {
                lastError = e;
                log.warn("[weather] 数据源 {} 失败，尝试下一个 city={} err={}",
                        provider.name(), city, e.toString());
            }
        }
        throw new IllegalStateException("所有天气数据源均不可用: " + city,
                lastError == null ? new IllegalStateException("无可用 WeatherProvider") : lastError);
    }

    /** Same fallback chain with a caller-owned Tavily credential. */
    public String getWeather(String city, String tavilyApiKey) {
        RuntimeException lastError = null;
        for (WeatherProvider provider : providers) {
            try {
                String result = provider instanceof TavilyWeatherProvider tavily
                        ? tavily.fetchWeather(city, tavilyApiKey)
                        : provider.fetchWeather(city);
                if (!StringUtils.isBlank(result)) return StringUtils.trim(result);
            } catch (RuntimeException e) {
                lastError = e;
            }
        }
        throw new IllegalStateException("所有天气数据源均不可用: " + city,
                lastError == null ? new IllegalStateException("无可用 WeatherProvider") : lastError);
    }
}
