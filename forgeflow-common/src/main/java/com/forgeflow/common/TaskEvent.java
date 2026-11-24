package com.forgeflow.common;

import java.time.Instant;

/**
 * One observable step of a task, used by the SSE endpoint.
 *
 * <p>{@code type} is {@code status} for lifecycle transitions, {@code subtask} for a planned
 * sub-agent starting or finishing, or {@code delta}/{@code reasoning} for streamed model
 * output. {@code source} names the subtask a delta came from, so a client can demultiplex
 * parallel sub-agents.</p>
 */
public record TaskEvent(String taskId, String type, TaskStatus status, String detail,
                        Instant at, String source) {

    public TaskEvent(String taskId, String type, TaskStatus status, String detail, Instant at) {
        this(taskId, type, status, detail, at, null);
    }

    public static TaskEvent status(String taskId, TaskStatus status, String detail) {
        return new TaskEvent(taskId, "status", status, detail, Instant.now());
    }

    public static TaskEvent subtask(String taskId, String detail) {
        return new TaskEvent(taskId, "subtask", null, detail, Instant.now());
    }

    public static TaskEvent delta(String taskId, String source, String detail) {
        return new TaskEvent(taskId, "delta", null, detail, Instant.now(), source);
    }

    public static TaskEvent reasoning(String taskId, String source, String detail) {
        return new TaskEvent(taskId, "reasoning", null, detail, Instant.now(), source);
    }
}
