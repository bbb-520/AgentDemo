package com.bbb.exercise.agentdemo1_0.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.bbb.exercise.agentdemo1_0.utils.AssertUtils;
import com.bbb.exercise.agentdemo1_0.utils.StringUtils;


/**
 * 景点推荐工具（暴露给模型的 {@code getAttraction}）。
 *
 * <p>把「城市 + 天气」拼成自然语言查询，委托 {@link TavilySearcher} 联网检索推荐及理由。
 * 典型的「模型决定调用 → 框架反射执行本方法 → 结果回传模型」链路末端。
 */
@Slf4j
@Component
public class AttractionTool {

    /** Tavily 查询模板：把城市与天气描述填入 */
    private static final String QUERY_TEMPLATE = "'%s' 在 '%s' 天气下最值得去的旅游景点推荐及理由";

    private final TavilySearcher tavilySearcher;

    public AttractionTool(TavilySearcher tavilySearcher) {
        this.tavilySearcher = tavilySearcher;
    }


    /**
     * 根据城市和当前天气搜索旅游景点推荐。
     *
     * @param city    城市名称（模型从用户问题中抽取）
     * @param weather 当前天气描述，可为空
     * @return Tavily 检索到的景点推荐文本
     */
    @Tool(description = "根据城市和当前天气，使用 Tavily 搜索并返回合适的旅游景点推荐及理由")
    public String getAttraction(@ToolParam(description = "城市名称，例如 北京") String city,
                                @ToolParam(description = "当前天气描述，例如 晴 26℃") String weather) {
        AssertUtils.isNotBlank(city, "城市名称不能为空");
        String cityName = StringUtils.trim(city);
        String weatherDesc = StringUtils.isBlank(weather) ? "" : StringUtils.trim(weather);
        // context 仅用于日志定位，便于区分同一次会话中的多次搜索
        return tavilySearcher.search("attraction:" + cityName + ":" + weatherDesc,
                String.format(QUERY_TEMPLATE, cityName, weatherDesc));
    }
}
