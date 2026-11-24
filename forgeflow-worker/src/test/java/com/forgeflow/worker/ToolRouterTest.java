package com.forgeflow.worker;

import com.forgeflow.worker.multiagent.Subtask;
import com.forgeflow.worker.multiagent.ToolDeniedException;
import com.forgeflow.worker.multiagent.ToolResult;
import com.forgeflow.worker.multiagent.ToolRouter;
import com.forgeflow.worker.tool.ToolCapability;
import com.forgeflow.worker.tool.ToolSpec;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolRouterTest {

    private final Subtask subtask = new Subtask("s1", 0, 0, "p");

    @Test
    void readToolsRunConcurrentlyUpToThePermitCount() throws Exception {
        ToolRouter router = new ToolRouter(Map.of(
                "search", new ToolSpec("search", ToolCapability.READ, false, null)),
                3, request -> true);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger max = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(10);

        for (int i = 0; i < 10; i++) {
            pool.submit(() -> router.invoke("ws", subtask, "search", Map.of(),
                    () -> {
                        int now = active.incrementAndGet();
                        max.accumulateAndGet(now, Math::max);
                        sleep(30);
                        active.decrementAndGet();
                        return ToolResult.ok("ok");
                    }));
        }
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);

        assertThat(max.get()).isBetween(2, 3);
    }

    @Test
    void writeToolsSerializePerWorkspace() throws Exception {
        ToolRouter router = new ToolRouter(Map.of(
                "write_note", new ToolSpec("write_note", ToolCapability.WRITE, false, null)),
                8, request -> true);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger max = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(8);

        for (int i = 0; i < 8; i++) {
            pool.submit(() -> router.invoke("ws", subtask, "write_note", Map.of(),
                    () -> {
                        max.accumulateAndGet(active.incrementAndGet(), Math::max);
                        sleep(20);
                        active.decrementAndGet();
                        return ToolResult.ok("ok");
                    }));
        }
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);

        assertThat(max.get()).isEqualTo(1);
    }

    @Test
    void dangerousToolRequiresApproval() {
        ToolRouter router = new ToolRouter(Map.of(
                "delete", new ToolSpec("delete", ToolCapability.DANGEROUS, true, null)),
                1, request -> false);

        assertThatThrownBy(() -> router.invoke("ws", subtask, "delete", Map.of(),
                () -> ToolResult.ok("deleted")))
                .isInstanceOf(ToolDeniedException.class);
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
