package com.forgeflow.common;

/**
 * Message published to the task queue. Only the task id and trace context are carried;
 * the DB is the source of truth.
 */
public record TaskMessage(String taskId, int attempt, String traceContext) {

    public TaskMessage(String taskId, int attempt) {
        this(taskId, attempt, null);
    }
}
