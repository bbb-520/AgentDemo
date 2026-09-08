package com.bbb.exercise.agentdemo1_0.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class TavilyConfig {

    @Value("${tavily.api-key}")
    private String apiKey;

    @Value("${tavily.base-url}")
    private String baseUrl;

    /**
     * 注册Tavily的响应式HTTP客户端接口
     *
     * @return
     */
    @Bean
    public WebClient tavilyWebClient(){
        return WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }
}
