package com.forgeflow.common;

import java.time.Instant;

public record TaskView(
        String id,
        String tenantId,
        TaskStatus status,
        long version,
        String result,
        String errorCode,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt
) {
    public static TaskView from(TaskRecord record) {
        return new TaskView(record.id(), record.tenantId(), record.status(), record.version(),
                record.result(), record.errorCode(), record.errorMessage(),
                record.createdAt(), record.updatedAt());
    }
}
