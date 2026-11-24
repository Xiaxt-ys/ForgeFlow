package com.forgeflow.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeflow.common.TaskEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;

/**
 * Redis pub/sub transport for task events, used when the API and the workers are separate
 * processes. Each SSE subscriber owns one channel subscription and disposes it on disconnect.
 *
 * <p>Pub/sub has no replay: a client that connects after an event misses it, which is why the
 * controller sends the current database state as the first frame.</p>
 */
@Component
@ConditionalOnProperty(prefix = "forgeflow.events", name = "mode", havingValue = "redis")
public class RedisTaskEventBus implements TaskEventBus, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(RedisTaskEventBus.class);
    private static final String TOPIC_PREFIX = "ff:task:events:";

    private final StringRedisTemplate redis;
    private final RedisMessageListenerContainer container;
    private final ObjectMapper mapper;

    public RedisTaskEventBus(StringRedisTemplate redis, RedisConnectionFactory connectionFactory,
                             ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
        this.container = new RedisMessageListenerContainer();
        this.container.setConnectionFactory(connectionFactory);
        this.container.afterPropertiesSet();
        // The container is created by hand, so nothing else would start its lifecycle.
        this.container.start();
    }

    @Override
    public void publish(TaskEvent event) {
        if (event == null || event.taskId() == null) {
            return;
        }
        try {
            redis.convertAndSend(topic(event.taskId()), mapper.writeValueAsString(event));
        } catch (Exception failure) {
            // Live events are best-effort; the JSONL trace and the database remain the record.
            log.warn("task event publish failed task={}: {}", event.taskId(), failure.getMessage());
        }
    }

    @Override
    public Flux<TaskEvent> stream(String taskId) {
        return Flux.create(sink -> {
            ChannelTopic topic = new ChannelTopic(topic(taskId));
            MessageListener listener = (message, pattern) -> {
                try {
                    String json = new String(message.getBody(), StandardCharsets.UTF_8);
                    sink.next(mapper.readValue(json, TaskEvent.class));
                } catch (Exception failure) {
                    sink.error(failure);
                }
            };
            container.addMessageListener(listener, topic);
            sink.onDispose(() -> container.removeMessageListener(listener, topic));
        });
    }

    private static String topic(String taskId) {
        return TOPIC_PREFIX + taskId;
    }

    @Override
    public void destroy() {
        try {
            container.destroy();
        } catch (Exception failure) {
            log.warn("redis event container shutdown failed: {}", failure.getMessage());
        }
    }
}
