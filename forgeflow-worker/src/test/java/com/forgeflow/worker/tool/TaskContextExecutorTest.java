package com.forgeflow.worker.tool;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TaskContextExecutorTest {

    @AfterEach
    void clear() {
        TaskContextHolder.clear();
    }

    @Test
    void copiesTaskIdentityToTheWorkerThread() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        AtomicReference<String> seen = new AtomicReference<>();
        try {
            TaskContextHolder.set("task-1", "tenant-a");
            new TaskContextExecutor(pool).execute(() ->
                    seen.set(TaskContextHolder.get() + "/" + TaskContextHolder.getTenantId()));
        } finally {
            pool.shutdown();
            pool.awaitTermination(5, TimeUnit.SECONDS);
            TaskContextHolder.clear();
        }

        assertThat(seen.get()).isEqualTo("task-1/tenant-a");
    }
}
