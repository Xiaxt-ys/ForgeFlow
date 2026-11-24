package com.forgeflow.bootstrap;

import com.forgeflow.common.TaskDispatcher;
import com.forgeflow.common.TaskMessage;
import com.forgeflow.worker.TaskWorker;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Local dispatch mode: no broker required, useful for demos and tests.
 * It preserves the same claim/lease/fencing path as the RabbitMQ listener.
 */
@Component
@ConditionalOnProperty(name = "forgeflow.dispatch.mode", havingValue = "local")
public class LocalTaskDispatcher implements TaskDispatcher {

    private final TaskWorker worker;
    private final ExecutorService pool = Executors.newFixedThreadPool(4, r -> {
        Thread thread = new Thread(r, "ff-local-dispatch");
        thread.setDaemon(true);
        return thread;
    });

    public LocalTaskDispatcher(TaskWorker worker) {
        this.worker = worker;
    }

    @Override
    public void dispatch(TaskMessage message) {
        pool.submit(() -> worker.handle(message));
    }

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }
}
