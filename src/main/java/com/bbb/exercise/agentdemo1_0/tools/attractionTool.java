package com.bbb.exercise.agentdemo1_0.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.bbb.exercise.agentdemo1_0.utils.AssertUtils;
import com.bbb.exercise.agentdemo1_0.utils.StringUtils;

/**
 * 根据城市与当前天气推荐旅游景点及理由（通过 {@link TavilySearcher} 调用 Tavily /search）。
 *
 * <p>工具入参使用扁平 {@code String} 参数（而非单参 DTO），让 Spring AI 生成扁平的
 * JSON Schema，模型需要输出的 token 更少、参数更不易出错。
 */
@Slf4j
@Component
public class attractionTool {

    private static final String QUERY_TEMPLATE = "'%s' 在 '%s' 天气下最值得去的旅游景点推荐及理由";

    private final TavilySearcher tavilySearcher;

    public attractionTool(TavilySearcher tavilySearcher) {
        this.tavilySearcher = tavilySearcher;
    }

    /**
     * 根据城市与天气推荐旅游景点
     *
     * @param city    城市名称
     * @param weather 当前天气描述（可选）
     * @return 景点推荐文本
     */
    @Tool(description = "根据城市和当前天气，使用 Tavily 搜索并返回合适的旅游景点推荐及理由")
    public String getAttraction(@ToolParam(description = "城市名称，例如 北京") String city,
                                @ToolParam(description = "当前天气描述，例如 晴 26℃") String weather) {
        AssertUtils.isNotBlank(city, "城市名称不能为空");
        String cityName = StringUtils.trim(city);
        String weatherDesc = StringUtils.isBlank(weather) ? "" : StringUtils.trim(weather);
        return tavilySearcher.search("attraction:" + cityName + ":" + weatherDesc,
                String.format(QUERY_TEMPLATE, cityName, weatherDesc));
    }
}
