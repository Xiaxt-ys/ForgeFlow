package com.forgeflow.worker.compensation;

import java.util.List;

/**
 * Records side-effect undo actions for a task and replays them when the turn fails.
 *
 * <p>Two implementations: an in-process one for local runs and tests, and a JDBC one that
 * survives a worker crash.</p>
 */
public interface CompensationTracker {

    void record(String taskId, Compensation compensation);

    /** Runs pending actions in reverse order and returns what was attempted. */
    List<String> compensateAll(String taskId);

    /** Drops the log after a successful turn. */
    void clear(String taskId);

    int size(String taskId);
}
