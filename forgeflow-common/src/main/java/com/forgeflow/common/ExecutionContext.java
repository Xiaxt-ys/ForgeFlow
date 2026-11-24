package com.forgeflow.common;

/**
 * Cancellation view passed to an executor. Executors should check {@link #cancelled()} at safe points.
 */
@FunctionalInterface
public interface ExecutionContext {
    boolean cancelled();

    static ExecutionContext never() {
        return () -> false;
    }
}
