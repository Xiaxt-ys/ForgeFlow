package com.forgeflow.worker.multiagent;

/**
 * Result of one subtask. Token and step counts are carried through so the aggregator can
 * report the whole plan's cost without a second lookup.
 */
public record SubtaskResult(String subtaskId, String output, boolean success,
                            long promptTokens, long completionTokens, int steps) {

    public SubtaskResult(String subtaskId, String output, boolean success) {
        this(subtaskId, output, success, 0, 0, 1);
    }
}
