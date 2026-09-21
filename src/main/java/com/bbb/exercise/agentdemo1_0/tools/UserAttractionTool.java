package com.bbb.exercise.agentdemo1_0.tools;

import com.bbb.exercise.agentdemo1_0.auth.UserApiKeyService.UserApiKeys;
import com.bbb.exercise.agentdemo1_0.utils.AssertUtils;
import com.bbb.exercise.agentdemo1_0.utils.StringUtils;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/** Tool instance carrying only the current user's credential for one chat request. */
public class UserAttractionTool {
    private static final String QUERY_TEMPLATE = "'%s' 在 '%s' 天气下最值得去的旅游景点推荐及理由";
    private final TavilySearcher searcher;
    private final String apiKey;

    public UserAttractionTool(TavilySearcher searcher, UserApiKeys keys) {
        this.searcher = searcher;
        this.apiKey = keys.tavilyApiKey();
    }

    @Tool(description = "根据城市和当前天气，使用 Tavily 搜索并返回合适的旅游景点推荐及理由")
    public String getAttraction(@ToolParam(description = "城市名称，例如 北京") String city,
                                @ToolParam(description = "当前天气描述，例如 晴 26℃") String weather) {
        AssertUtils.isNotBlank(city, "城市名称不能为空");
        String cityName = StringUtils.trim(city);
        String weatherDesc = StringUtils.isBlank(weather) ? "" : StringUtils.trim(weather);
        return searcher.search(apiKey, "attraction:" + cityName + ":" + weatherDesc,
                String.format(QUERY_TEMPLATE, cityName, weatherDesc));
    }
}
