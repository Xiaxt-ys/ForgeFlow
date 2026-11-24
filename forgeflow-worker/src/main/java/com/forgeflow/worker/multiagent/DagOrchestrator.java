package com.forgeflow.worker.multiagent;

import com.forgeflow.common.ExecutionContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Dependency-aware scheduler for a {@link TaskPlan}.
 *
 * <p>Subtask with no unfinished dependency runs immediately; independent siblings run in
 * parallel up to {@code concurrency}. A failed dependency cascades: every dependent
 * subtask is marked SKIPPED instead of executing on missing input. Cancellation stops new
 * dispatch and skips whatever has not started.</p>
 */
public final class DagOrchestrator {

    public OrchestrationResult run(TaskPlan plan, SubAgent subAgent, ToolRouter toolRouter,
                                   AgentEventBus events, ExecutionContext context,
                                   int concurrency, long timeoutMs) throws InterruptedException {
        long start = System.currentTimeMillis();
        int total = plan.subtasks().size();
        Map<String, Subtask> tasks = new LinkedHashMap<>();
        for (PlannedSubtask planned : plan.subtasks()) {
            tasks.put(planned.id(), new Subtask(planned.id(), planned.prompt(), planned.dependsOn()));
        }

        Map<String, SubtaskResult> results = new ConcurrentHashMap<>();
        Set<String> succeeded = ConcurrentHashMap.newKeySet();
        Set<String> settled = ConcurrentHashMap.newKeySet();
        Set<String> dispatched = ConcurrentHashMap.newKeySet();
        boolean cancelled = false;
        boolean timedOut = false;

        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, concurrency), runnable -> {
            Thread thread = new Thread(runnable, "ff-plan");
            thread.setDaemon(true);
            return thread;
        });
        ExecutorCompletionService<SubtaskResult> completion = new ExecutorCompletionService<>(pool);
        int running = 0;
        long deadline = start + Math.max(1, timeoutMs);

        try {
            while (settled.size() < total) {
                if (context != null && context.cancelled()) {
                    cancelled = true;
                    break;
                }
                for (PlannedSubtask planned : plan.subtasks()) {
                    if (settled.contains(planned.id())) {
                        continue;
                    }
                    boolean blocked = planned.dependsOn().stream()
                            .anyMatch(dependency -> settled.contains(dependency)
                                    && !succeeded.contains(dependency));
                    if (blocked) {
                        skip(tasks.get(planned.id()), events);
                        settled.add(planned.id());
                        results.put(planned.id(), new SubtaskResult(planned.id(), "skipped: dependency failed", false));
                        continue;
                    }
                    boolean ready = succeeded.containsAll(planned.dependsOn());
                    if (ready && dispatched.add(planned.id())) {
                        String subtaskId = planned.id();
                        String upstream = upstreamContext(planned.dependsOn(), results);
                        if (!upstream.isEmpty()) {
                            tasks.get(subtaskId).appendPrompt(upstream);
                        }
                        completion.submit(() -> {
                            try {
                                return execute(tasks.get(subtaskId), subAgent, toolRouter, events);
                            } catch (Throwable failure) {
                                return new SubtaskResult(subtaskId, "error: " + failure, false);
                            }
                        });
                        running++;
                    }
                }
                if (running == 0) {
                    // Nothing runnable and nothing in flight: the rest cannot start.
                    break;
                }
                long waitMs = Math.max(1, Math.min(1000, deadline - System.currentTimeMillis()));
                Future<SubtaskResult> completed = completion.poll(waitMs, TimeUnit.MILLISECONDS);
                if (completed == null) {
                    if (System.currentTimeMillis() >= deadline) {
                        timedOut = true;
                        break;
                    }
                    continue;
                }
                running--;
                SubtaskResult result;
                try {
                    result = completed.get();
                } catch (java.util.concurrent.ExecutionException escaped) {
                    // The callable already converts throwables into a failed result.
                    result = new SubtaskResult("unknown", "error: " + escaped.getCause(), false);
                }
                results.put(result.subtaskId(), result);
                settled.add(result.subtaskId());
                if (result.success()) {
                    succeeded.add(result.subtaskId());
                }
            }
        } finally {
            pool.shutdownNow();
        }

        // Anything not settled (cancelled, timed out, unreachable) never ran.
        for (PlannedSubtask planned : plan.subtasks()) {
            if (!settled.contains(planned.id())) {
                skip(tasks.get(planned.id()), events);
                settled.add(planned.id());
                results.put(planned.id(), new SubtaskResult(planned.id(),
                        cancelled ? "skipped: cancelled" : "skipped: not started", false));
            }
        }

        List<SubtaskResult> ordered = new ArrayList<>(total);
        int ok = 0;
        int failed = 0;
        int skipped = 0;
        for (PlannedSubtask planned : plan.subtasks()) {
            SubtaskResult result = results.get(planned.id());
            ordered.add(result);
            if (result.success()) {
                ok++;
            } else if (result.output() != null && result.output().startsWith("skipped")) {
                skipped++;
            } else {
                failed++;
            }
        }
        return new OrchestrationResult(ordered, ok, failed, skipped, cancelled, timedOut,
                System.currentTimeMillis() - start);
    }

    private static SubtaskResult execute(Subtask subtask, SubAgent subAgent, ToolRouter toolRouter,
                                         AgentEventBus events) {
        String workerId = "plan-" + Thread.currentThread().getId();
        if (!subtask.claim(workerId)) {
            return new SubtaskResult(subtask.id(), "skipped: claim rejected", false);
        }
        subtask.start();
        events.emit(new SubtaskLifecycleEvent(subtask.id(), "RUNNING"));
        try {
            SubtaskResult result = subAgent.execute(subtask, new AgentContext(), toolRouter, events);
            if (result.success()) {
                subtask.succeed();
            } else {
                subtask.fail();
            }
            events.emit(new SubtaskLifecycleEvent(subtask.id(), subtask.status().name()));
            return result;
        } catch (RuntimeException failure) {
            subtask.fail();
            events.emit(new SubtaskLifecycleEvent(subtask.id(), "FAILED"));
            return new SubtaskResult(subtask.id(), "error: " + failure.getMessage(), false);
        }
    }

    private static void skip(Subtask subtask, AgentEventBus events) {
        subtask.skip();
        events.emit(new SubtaskLifecycleEvent(subtask.id(), "SKIPPED"));
    }

    /**
     * Hands a dependent subtask the outputs it was waiting for. Without this, dependency
     * edges only enforce ordering — the downstream agent would have no data to work on.
     */
    private static String upstreamContext(List<String> dependsOn, Map<String, SubtaskResult> results) {
        if (dependsOn == null || dependsOn.isEmpty()) {
            return "";
        }
        StringBuilder context = new StringBuilder("\n\n[upstream results]");
        for (String dependency : dependsOn) {
            SubtaskResult result = results.get(dependency);
            if (result != null) {
                context.append("\n- ").append(dependency).append(": ").append(summarize(result.output()));
            }
        }
        return context.length() == 0 ? "" : context.toString();
    }

    private static String summarize(String output) {
        if (output == null) {
            return "";
        }
        return output.length() <= 2000 ? output : output.substring(0, 2000) + "…";
    }
}
