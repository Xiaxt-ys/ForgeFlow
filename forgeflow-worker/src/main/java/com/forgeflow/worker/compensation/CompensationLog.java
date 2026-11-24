package com.forgeflow.worker.compensation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Per-task compensation log. Side-effecting tools register an undo action after they
 * succeed; if the turn later fails (or is parked for a human), the actions are replayed in
 * reverse order before the failure leaves the worker.
 *
 * <p>The log is in-process. A tool whose undo lives in another system still works — the
 * action is just a remote call — but a worker crash loses the log, which is why irreversible
 * tools must be marked as such rather than silently skipped.</p>
 */
@Component
@ConditionalOnProperty(prefix = "forgeflow.compensation", name = "mode",
        havingValue = "memory", matchIfMissing = true)
public class CompensationLog implements CompensationTracker {

    private static final Logger log = LoggerFactory.getLogger(CompensationLog.class);

    private final Map<String, Deque<Compensation>> logs = new ConcurrentHashMap<>();

    public void record(String taskId, Compensation compensation) {
        if (taskId == null || compensation == null) {
            return;
        }
        logs.computeIfAbsent(taskId, id -> new ConcurrentLinkedDeque<>()).addLast(compensation);
    }

    /** Runs every recorded undo in reverse order and returns what was done. */
    public List<String> compensateAll(String taskId) {
        Deque<Compensation> actions = taskId == null ? null : logs.remove(taskId);
        if (actions == null || actions.isEmpty()) {
            return List.of();
        }
        List<String> done = new ArrayList<>();
        Compensation action;
        while ((action = actions.pollLast()) != null) {
            try {
                action.undo().run();
                done.add(action.tool() + ": " + action.description());
            } catch (RuntimeException failure) {
                log.error("compensation.failed task={} tool={}", taskId, action.tool(), failure);
                done.add(action.tool() + ": FAILED");
            }
        }
        log.info("compensation.done task={} actions={}", taskId, done);
        return done;
    }

    /** Drops the log after a successful turn — there is nothing to undo. */
    public void clear(String taskId) {
        if (taskId != null) {
            logs.remove(taskId);
        }
    }

    public int size(String taskId) {
        Deque<Compensation> actions = taskId == null ? null : logs.get(taskId);
        return actions == null ? 0 : actions.size();
    }
}
