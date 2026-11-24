package com.forgeflow.common;

import java.time.Instant;

/**
 * Persistent task state. {@code version} is the fencing/optimistic-lock token.
 */
public record TaskRecord(
        String id,
        String tenantId,
        TaskStatus status,
        long version,
        String workerId,
        String idempotencyKey,
        String prompt,
        String result,
        String errorCode,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt
) {
    public boolean terminal() {
        return status.terminal();
    }
}
