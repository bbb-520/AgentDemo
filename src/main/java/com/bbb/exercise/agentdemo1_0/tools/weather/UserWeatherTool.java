package com.bbb.exercise.agentdemo1_0.tools.weather;

import com.bbb.exercise.agentdemo1_0.auth.UserApiKeyService.UserApiKeys;
import com.bbb.exercise.agentdemo1_0.utils.AssertUtils;
import com.bbb.exercise.agentdemo1_0.utils.StringUtils;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/** Tool instance carrying the current user's Tavily credential for fallback weather lookup. */
public class UserWeatherTool {
    private final WeatherService weatherService;
    private final String tavilyApiKey;

    public UserWeatherTool(WeatherService weatherService, UserApiKeys keys) {
        this.weatherService = weatherService;
        this.tavilyApiKey = keys.tavilyApiKey();
    }

    @Tool(description = "查询指定城市的实时天气，返回温度、天气状况、湿度、风速等信息")
    public String getWeather(@ToolParam(description = "要查询天气的城市名称，例如 北京") String city) {
        AssertUtils.isNotBlank(city, "城市名称不能为空");
        return weatherService.getWeather(StringUtils.trim(city), tavilyApiKey);
    }
}
