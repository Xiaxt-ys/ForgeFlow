package com.forgeflow.worker.multiagent;

import com.forgeflow.worker.tool.ToolCapability;
import com.forgeflow.worker.tool.ToolSpec;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Read tools run concurrently under a semaphore; write tools serialize on a
 * workspace lock; dangerous tools must pass the approval hook before execution.
 */
public final class ToolRouter {

    private final Map<String, ToolSpec> specs;
    private final Semaphore readPermits;
    private final ConcurrentHashMap<String, ReentrantLock> workspaceLocks = new ConcurrentHashMap<>();
    private final ApprovalHook approvalHook;

    public ToolRouter(Map<String, ToolSpec> specs, int readConcurrency, ApprovalHook approvalHook) {
        this.specs = Map.copyOf(specs);
        this.readPermits = new Semaphore(readConcurrency, true);
        this.approvalHook = approvalHook;
    }

    public ToolResult invoke(String workspaceId, Subtask subtask, String toolName,
                             Map<String, Object> args, Supplier<ToolResult> action) {
        ToolSpec spec = specs.get(toolName);
        if (spec == null) {
            throw new IllegalArgumentException("unknown tool: " + toolName);
        }
        if (spec.capability() == ToolCapability.DANGEROUS) {
            ApprovalRequest request = new ApprovalRequest(workspaceId, subtask.id(), toolName, args);
            if (!approvalHook.approve(request)) {
                throw new ToolDeniedException(toolName);
            }
        }
        if (spec.capability() == ToolCapability.READ) {
            try {
                readPermits.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for read permit", e);
            }
            try {
                return action.get();
            } finally {
                readPermits.release();
            }
        }
        ReentrantLock lock = workspaceLocks.computeIfAbsent(workspaceId, id -> new ReentrantLock(true));
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }
}
