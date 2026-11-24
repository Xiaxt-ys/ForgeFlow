package com.forgeflow.worker.tool;

import java.util.concurrent.Executor;

/**
 * Copies the task identity onto the thread that actually runs the work.
 *
 * <p>agents-flex runs tool calls on its own executor, so a plain ThreadLocal set on the
 * caller is invisible there — audit, RBAC and approval checks would see a null task.
 * This wrapper captures the identity at submit time and restores it on the worker thread.</p>
 */
public final class TaskContextExecutor implements Executor {

    private final Executor delegate;

    public TaskContextExecutor(Executor delegate) {
        this.delegate = delegate;
    }

    @Override
    public void execute(Runnable command) {
        String taskId = TaskContextHolder.get();
        String tenantId = TaskContextHolder.getTenantId();
        delegate.execute(() -> {
            TaskContextHolder.set(taskId, tenantId);
            try {
                command.run();
            } finally {
                TaskContextHolder.clear();
            }
        });
    }
}
