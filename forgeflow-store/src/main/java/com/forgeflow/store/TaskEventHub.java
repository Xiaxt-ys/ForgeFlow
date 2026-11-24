package com.forgeflow.store;

import com.forgeflow.common.TaskEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process fan-out of task events for SSE subscribers.
 *
 * <p>This is enough when the API and the worker share a JVM (the default profile). When
 * workers run as separate processes, replace the transport with Redis pub/sub or a
 * RabbitMQ fanout exchange; publishers and the controller keep the same API.</p>
 */
@Component
@ConditionalOnProperty(prefix = "forgeflow.events", name = "mode",
        havingValue = "memory", matchIfMissing = true)
public class TaskEventHub implements TaskEventBus {

    private static final int BUFFER = 256;

    private final Map<String, Sinks.Many<TaskEvent>> sinks = new ConcurrentHashMap<>();

    public void publish(TaskEvent event) {
        Sinks.Many<TaskEvent> sink = sinks.computeIfAbsent(event.taskId(), id -> newSink());
        sink.tryEmitNext(event);
        if (event.status() != null && event.status().terminal()) {
            sink.tryEmitComplete();
            // Late subscribers get the current state from the database instead.
            sinks.remove(event.taskId(), sink);
        }
    }

    public Flux<TaskEvent> stream(String taskId) {
        return sinks.computeIfAbsent(taskId, id -> newSink()).asFlux();
    }

    private static Sinks.Many<TaskEvent> newSink() {
        return Sinks.many().multicast().onBackpressureBuffer(BUFFER);
    }
}
