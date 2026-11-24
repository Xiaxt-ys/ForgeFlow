package com.forgeflow.worker.compensation;

import java.util.Map;
import java.util.UUID;

/**
 * One undo action recorded after a side-effecting tool call succeeded.
 *
 * <p>{@code operationId} is stable for the life of the record: it is generated once when the
 * action is first registered, persisted, and handed back to the compensator on every retry.
 * An undo that may run more than once must use it as its idempotency key.</p>
 */
public record Compensation(String operationId, String tool, String description,
                           Runnable undo, Map<String, Object> args) {

    public Compensation {
        operationId = operationId == null || operationId.isBlank()
                ? UUID.randomUUID().toString()
                : operationId;
        args = args == null ? Map.of() : Map.copyOf(args);
    }

    public Compensation(String tool, String description, Runnable undo) {
        this(null, tool, description, undo, Map.of());
    }

    public Compensation(String tool, String description, Runnable undo,
                        Map<String, Object> args) {
        this(null, tool, description, undo, args);
    }
}
