package com.forgeflow.store;

import java.time.Instant;

/**
 * One durable fact extracted from a task, with the task it came from for provenance.
 *
 * <p>Lifecycle fields: {@code importance} seeds ranking, {@code hits}/{@code lastUsedAt} record
 * reinforcement (a fact that keeps being injected into context is not decayed), {@code status}
 * moves between ACTIVE and ARCHIVED, and {@code version} increments on every value change.</p>
 */
public record SessionFact(String factKey, String factValue, String sourceTaskId,
                          Instant updatedAt, double importance, int hits,
                          Instant lastUsedAt, String status, int version) {

    public static final String ACTIVE = "ACTIVE";
    public static final String ARCHIVED = "ARCHIVED";

    /** Backwards-compatible shape for callers that only care about the payload. */
    public SessionFact(String factKey, String factValue, String sourceTaskId, Instant updatedAt) {
        this(factKey, factValue, sourceTaskId, updatedAt, 0.5, 0, null, ACTIVE, 1);
    }

    public boolean active() {
        return ACTIVE.equals(status);
    }
}
