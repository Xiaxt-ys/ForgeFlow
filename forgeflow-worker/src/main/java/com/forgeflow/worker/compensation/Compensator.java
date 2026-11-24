package com.forgeflow.worker.compensation;

import java.util.Map;

/**
 * Supplies the undo action for a tool, or null when the side effect cannot be reversed.
 *
 * <p>The {@code operationId} overload is the one the durable log calls: the id is stable
 * across retries, so an undo can deduplicate itself with it.</p>
 */
@FunctionalInterface
public interface Compensator {

    Compensation compensation(String taskId, String tool, Map<String, Object> args);

    default Compensation compensation(String taskId, String operationId, String tool,
                                      Map<String, Object> args) {
        return compensation(taskId, tool, args);
    }
}
