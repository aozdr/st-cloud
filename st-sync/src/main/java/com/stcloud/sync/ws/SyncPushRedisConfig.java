package com.stcloud.sync.ws;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.nio.charset.StandardCharsets;

/**
 * 同步变更推送的跨节点扇出：DB 写入仍由 RocketMQ 单消费者完成，
 * 消费者把 tenantId:userId:logId 发布到 Redis，各节点只推给本地 WebSocket 会话。
 */
@Slf4j
@Configuration
public class SyncPushRedisConfig {

    public static final String CHANNEL = "stcloud:sync:push";

    @Bean
    public RedisMessageListenerContainer syncPushContainer(
            ObjectProvider<RedisConnectionFactory> connectionFactoryProvider,
            ObjectProvider<SyncWebSocketHandler> handlerProvider) {
        RedisConnectionFactory connectionFactory = connectionFactoryProvider.getIfAvailable();
        SyncWebSocketHandler handler = handlerProvider.getIfAvailable();
        if (connectionFactory == null || handler == null) {
            log.info("Redis 或 WebSocket 处理器缺失，跨节点同步推送未启用");
            return null;
        }
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener((message, pattern) -> {
            String payload = new String(message.getBody(), StandardCharsets.UTF_8);
            String[] parts = payload.split(":");
            if (parts.length < 3) {
                return;
            }
            try {
                long tenantId = Long.parseLong(parts[0]);
                long userId = Long.parseLong(parts[1]);
                long logId = Long.parseLong(parts[2]);
                handler.sendToTenantUser(tenantId, userId,
                        "{\"event\":\"change\",\"userId\":" + userId + ",\"logId\":" + logId + "}");
            } catch (NumberFormatException e) {
                log.warn("同步推送消息格式非法：{}", payload);
            }
        }, new ChannelTopic(CHANNEL));
        return container;
    }
}
