package com.bbb.exercise.agentdemo1_0.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class TavilyConfig {

    @Bean
    public WebClient tavilyWebClient(WebClient.Builder builder, TavilyProperties properties){
        return builder
                .baseUrl(properties.getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + properties.getApiKey())
                .defaultHeader("Content-Type", "application/json")
                .build();
    }
}
