package com.bbb.exercise.agentdemo1_0.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/** 外部服务客户端工厂，避免业务组件自行创建 WebClient 和散落超时配置。 */
@Configuration
public class ExternalClientConfig {

    @Bean("openMeteoWebClient")
    WebClient openMeteoWebClient(WebClient.Builder builder) {
        return builder.build();
    }
}
