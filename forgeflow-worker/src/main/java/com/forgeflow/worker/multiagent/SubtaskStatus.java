package com.forgeflow.worker.multiagent;

public enum SubtaskStatus {
    PENDING,
    CLAIMED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    /** A dependency failed or the task was cancelled before this subtask could start. */
    SKIPPED
}
