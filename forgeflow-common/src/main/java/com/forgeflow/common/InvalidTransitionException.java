package com.forgeflow.common;

public class InvalidTransitionException extends RuntimeException {
    public InvalidTransitionException(String taskId, TaskStatus from, TaskStatus to) {
        super("invalid task transition: " + taskId + " " + from + " -> " + to);
    }
}
