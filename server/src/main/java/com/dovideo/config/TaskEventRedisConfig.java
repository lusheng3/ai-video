package com.dovideo.config;

import com.dovideo.service.TaskEventService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * 任务事件 Redis 订阅容器：把任意实例发布到 dovideo:task-events 的事件，
 * 转发给本实例上的 SseEmitter，实现多实例部署下的实时推送。
 */
@Configuration
public class TaskEventRedisConfig {

    @Bean
    public RedisMessageListenerContainer taskEventListenerContainer(
            RedisConnectionFactory connectionFactory,
            TaskEventService taskEventService) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(taskEventService, new ChannelTopic(TaskEventService.REDIS_CHANNEL));
        return container;
    }
}
