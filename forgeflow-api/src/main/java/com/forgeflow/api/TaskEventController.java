package com.forgeflow.api;

import com.forgeflow.common.TaskEvent;
import com.forgeflow.common.TaskView;
import com.forgeflow.store.TaskEventBus;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Server-sent events for one task. The stream starts with the current state read from the
 * database, then forwards live transitions and subtask events until a terminal status.
 *
 * <p>A quiet stream would otherwise never notice a client that vanished, leaving its Redis
 * subscription behind. A periodic comment frame forces a write, which surfaces the broken
 * connection and releases the subscription.</p>
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskEventController {

    private static final long TIMEOUT_MS = 30 * 60 * 1000L;

    private final TaskService tasks;
    private final TaskEventBus hub;
    private final long heartbeatMs;
    private final ScheduledExecutorService heartbeats =
            Executors.newScheduledThreadPool(1, runnable -> {
                Thread thread = new Thread(runnable, "ff-sse-heartbeat");
                thread.setDaemon(true);
                return thread;
            });

    public TaskEventController(TaskService tasks, TaskEventBus hub,
                               @Value("${forgeflow.sse.heartbeat-ms:15000}") long heartbeatMs) {
        this.tasks = tasks;
        this.hub = hub;
        this.heartbeatMs = Math.max(1_000L, heartbeatMs);
    }

    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable("id") String id) {
        TaskView current = tasks.get(id); // 404 before opening the stream
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);

        Disposable subscription = hub.stream(id)
                .startWith(new TaskEvent(id, "status", current.status(), "current",
                        java.time.Instant.now()))
                .subscribe(event -> send(emitter, event), emitter::completeWithError, emitter::complete);

        ScheduledFuture<?> heartbeat = heartbeats.scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event().comment("ping"));
            } catch (IOException | IllegalStateException disconnected) {
                emitter.completeWithError(disconnected);
            }
        }, heartbeatMs, heartbeatMs, TimeUnit.MILLISECONDS);

        Runnable cleanup = () -> {
            heartbeat.cancel(false);
            subscription.dispose();
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(() -> {
            cleanup.run();
            emitter.complete();
        });
        emitter.onError(error -> cleanup.run());
        return emitter;
    }

    private static void send(SseEmitter emitter, TaskEvent event) {
        try {
            emitter.send(SseEmitter.event().name("task").data(event, MediaType.APPLICATION_JSON));
            if (event.status() != null && event.status().terminal()) {
                emitter.complete();
            }
        } catch (IOException failure) {
            emitter.completeWithError(failure);
        }
    }

    @PreDestroy
    void shutdown() {
        heartbeats.shutdownNow();
    }
}
