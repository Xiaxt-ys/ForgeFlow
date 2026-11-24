package com.forgeflow.store;

import com.forgeflow.common.TaskEvent;
import reactor.core.publisher.Flux;

/**
 * Fan-out of task events to SSE subscribers. Two implementations: in-process for a single
 * JVM, Redis pub/sub when the API and workers run as separate processes.
 */
public interface TaskEventBus {

    void publish(TaskEvent event);

    Flux<TaskEvent> stream(String taskId);
}
