package com.forgeflow.common;

import java.util.EnumSet;
import java.util.Set;

/**
 * Task lifecycle. Transitions are guarded by conditional SQL updates in the store.
 */
public enum TaskStatus {
    QUEUED,
    CLAIMED,
    RUNNING,
    WAITING_APPROVAL,
    /** The agent could not produce a usable result; a human must resume or reject it. */
    WAITING_HUMAN,
    CANCELLING,
    CANCELLED,
    SUCCEEDED,
    FAILED;

    private static final Set<TaskStatus> TERMINAL = EnumSet.of(CANCELLED, SUCCEEDED, FAILED);

    public boolean terminal() {
        return TERMINAL.contains(this);
    }

    public boolean canTransitionTo(TaskStatus next) {
        return switch (this) {
            case QUEUED -> next == CLAIMED || next == CANCELLED;
            case CLAIMED -> next == RUNNING || next == QUEUED || next == CANCELLED || next == FAILED;
            case RUNNING -> next == SUCCEEDED || next == FAILED || next == CANCELLING
                    || next == WAITING_APPROVAL || next == WAITING_HUMAN;
            case WAITING_APPROVAL -> next == RUNNING || next == FAILED || next == CANCELLING;
            case WAITING_HUMAN -> next == QUEUED || next == FAILED || next == CANCELLED;
            case CANCELLING -> next == CANCELLED || next == FAILED;
            case CANCELLED, SUCCEEDED, FAILED -> false;
        };
    }
}
