package com.forgeflow.store;

import java.time.Instant;

/**
 * Rolling summary of a session plus its structured state (decisions, constraints) and a version
 * used for compare-and-set writes when two workers learn the same session concurrently.
 */
public record SessionSummary(String sessionId, String summary, String structuredState,
                             int version, Instant updatedAt) {

    public SessionSummary(String sessionId, String summary, String structuredState,
                          Instant updatedAt) {
        this(sessionId, summary, structuredState, 0, updatedAt);
    }
}
