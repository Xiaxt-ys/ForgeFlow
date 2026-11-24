package com.forgeflow.worker.multiagent;

import java.util.List;

/**
 * Outcome of one planned run. Results are ordered by the plan, not by completion time,
 * so the aggregator can present them deterministically.
 */
public record OrchestrationResult(List<SubtaskResult> results, int succeeded, int failed,
                                  int skipped, boolean cancelled, boolean timedOut,
                                  long elapsedMs) {

    public OrchestrationResult {
        results = results == null ? List.of() : List.copyOf(results);
    }

    public boolean allSucceeded() {
        return failed == 0 && skipped == 0;
    }
}
