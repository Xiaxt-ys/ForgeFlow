package com.forgeflow.store;

import java.time.Instant;

/**
 * A superseded or conflicting value of a fact. Memory refuses to lose information silently:
 * latest-wins overwrites the current value but the previous one stays here, so a poisoned or
 * wrong merge can be traced back to the task that produced it.
 */
public record FactHistory(String id, String factKey, String oldValue, String newValue,
                          String sourceTaskId, String kind, Instant createdAt) {
    /** The new value refined the old one (containment); no conflict. */
    public static final String REFINED = "REFINED";
    /** Two incompatible values for the same key. */
    public static final String CONFLICT = "CONFLICT";
}
