package com.bbb.exercise.agentdemo1_0.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Tavily 连接及查询参数。密钥只从环境变量注入，不进入代码。 */
@ConfigurationProperties(prefix = "tavily")
public class TavilyProperties {
    private String apiKey;
    private String baseUrl;
    private String searchDepth = "advanced";
    private int maxResults = 5;
    private boolean includeAnswer = true;

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getSearchDepth() { return searchDepth; }
    public void setSearchDepth(String searchDepth) { this.searchDepth = searchDepth; }
    public int getMaxResults() { return maxResults; }
    public void setMaxResults(int maxResults) { this.maxResults = maxResults; }
    public boolean isIncludeAnswer() { return includeAnswer; }
    public void setIncludeAnswer(boolean includeAnswer) { this.includeAnswer = includeAnswer; }
}
