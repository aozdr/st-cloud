package com.stcloud.common.ratelimit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * 多节点限速配置生效时的跨节点失效通道。
 * 只在 mode=redis 时注册，单机 local 模式不依赖 Redis。
 */
@Configuration
@ConditionalOnProperty(name = "stcloud.rate-limit.mode", havingValue = "redis")
public class RateLimitRedisConfig {

    @Bean
    public RedisMessageListenerContainer rateLimitInvalidationContainer(
            RedisConnectionFactory connectionFactory,
            SpeedLimitCache speedLimitCache,
            RateLimitProperties properties) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener((message, pattern) -> speedLimitCache.evict(),
                new ChannelTopic(properties.getInvalidationChannel()));
        return container;
    }
}
