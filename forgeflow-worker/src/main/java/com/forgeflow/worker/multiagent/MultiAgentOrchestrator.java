package com.forgeflow.worker.multiagent;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs rounds x subtasks with a bounded worker pool. Every subtask is delivered twice
 * to simulate duplicate messages; CAS claim must make exactly one delivery win.
 */
public final class MultiAgentOrchestrator {

    private final SubAgent subAgent;
    private final ToolRouter toolRouter;
    private final AgentEventBus events;

    public MultiAgentOrchestrator(SubAgent subAgent, ToolRouter toolRouter, AgentEventBus events) {
        this.subAgent = subAgent;
        this.toolRouter = toolRouter;
        this.events = events;
    }

    public MultiAgentReport run(int rounds, int subtasksPerRound, int concurrency)
            throws InterruptedException {
        List<Subtask> subtasks = new ArrayList<>(rounds * subtasksPerRound);
        for (int round = 0; round < rounds; round++) {
            for (int index = 0; index < subtasksPerRound; index++) {
                subtasks.add(new Subtask("r" + round + "-s" + index, round, index,
                        "task r" + round + " s" + index));
            }
        }

        int total = subtasks.size();
        ExecutorService pool = Executors.newFixedThreadPool(concurrency, r -> {
            Thread thread = new Thread(r, "ff-subagent");
            thread.setDaemon(true);
            return thread;
        });
        CountDownLatch latch = new CountDownLatch(total * 2);
        AtomicInteger claimed = new AtomicInteger();
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger duplicates = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        Set<String> executed = ConcurrentHashMap.newKeySet();
        long start = System.currentTimeMillis();

        for (Subtask subtask : subtasks) {
            pool.submit(() -> attempt(subtask, latch, claimed, succeeded, duplicates,
                    rejected, executed));
            // Duplicate delivery: with a broken claim, this would execute the same subtask twice.
            pool.submit(() -> attempt(subtask, latch, claimed, succeeded, duplicates,
                    rejected, executed));
        }

        if (!latch.await(120, TimeUnit.SECONDS)) {
            pool.shutdownNow();
            throw new IllegalStateException("multi-agent run timed out");
        }
        pool.shutdownNow();
        long elapsed = System.currentTimeMillis() - start;
        int executedCount = executed.size();
        return new MultiAgentReport(rounds, subtasksPerRound, concurrency, total,
                claimed.get(), executedCount, succeeded.get(), duplicates.get(),
                total - executedCount, rejected.get(), elapsed);
    }

    private void attempt(Subtask subtask, CountDownLatch latch, AtomicInteger claimed,
                         AtomicInteger succeeded, AtomicInteger duplicates,
                         AtomicInteger rejected, Set<String> executed) {
        try {
            String workerId = "w-" + Thread.currentThread().getId();
            if (!subtask.claim(workerId)) {
                rejected.incrementAndGet();
                return;
            }
            claimed.incrementAndGet();
            subtask.start();
            events.emit(new SubtaskLifecycleEvent(subtask.id(), "RUNNING"));
            if (!executed.add(subtask.id())) {
                duplicates.incrementAndGet();
            }
            AgentContext context = new AgentContext();
            SubtaskResult result = subAgent.execute(subtask, context, toolRouter, events);
            if (result.success()) {
                subtask.succeed();
                succeeded.incrementAndGet();
            } else {
                subtask.fail();
            }
            events.emit(new SubtaskLifecycleEvent(subtask.id(), subtask.status().name()));
        } catch (Exception failure) {
            subtask.fail();
        } finally {
            latch.countDown();
        }
    }
}
