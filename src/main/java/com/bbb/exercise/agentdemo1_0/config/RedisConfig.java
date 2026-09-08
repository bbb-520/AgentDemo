package com.bbb.exercise.agentdemo1_0.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis 配置。
 *
 * <p>本项目用<b>普通 Redis</b>（非 Redis Stack）承载会话记忆二级存储：
 * <ul>
 *     <li>{@link StringRedisTemplate}：读写消息 Sorted Set 与摘要
 *         —— 由 Spring Boot 自动装配，读 {@code spring.data.redis.*}；</li>
 *     <li>{@link #chatMemoryObjectMapper()}：会话记忆专用 ObjectMapper，
 *         注册 JSR310 时间类型模块，用于 Message 自定义 DTO 与 JSON 间转换。
 *         <b>不使用</b> {@code activateDefaultTyping}（有 RCE 风险），
 *         改用自定义 DTO + 类型字段规避 Message 多态反序列化问题。</li>
 * </ul>
 *
 * <p>连接参数（host/port/password/lettuce pool）沿用 Spring Boot 自动装配的 {@code spring.data.redis.*}；
 * {@link StringRedisTemplate} 与 {@code LettuceConnectionFactory} 均由自动装配提供，本类不再重复声明，
 * 以免硬编码覆盖 yml 配置。
 *
 * @see com.bbb.exercise.agentdemo1_0.memory.RedisChatMemoryRepository
 */
@Configuration
public class RedisConfig {

    /**
     * 会话记忆专用 ObjectMapper。
     *
     * <p>注册 {@link JavaTimeModule} 以支持 {@code Instant/Duration} 等时间类型；
     * 关闭 {@code WRITE_DATES_AS_TIMESTAMPS} 使时间以 ISO-8601 字符串存储，便于人工排查 Redis。
     */
    @Bean("chatMemoryObjectMapper")
    public ObjectMapper chatMemoryObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }
}
