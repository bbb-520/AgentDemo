package com.bbb.exercise.agentdemo1_0.tools.weather;

import com.bbb.exercise.agentdemo1_0.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 天气查询门面：按 {@code @Order} 顺序遍历所有 {@link WeatherProvider}，
 * 首个成功者胜出，全部失败才抛出异常。
 *
 * <p>调用方（如 weatherTool）只依赖本类，不关心具体有几个数据源、
 * 优先级如何 —— 新增/下线数据源对调用方完全透明。
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
}
