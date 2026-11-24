package com.forgeflow.worker.multiagent;

@FunctionalInterface
public interface ApprovalHook {
    boolean approve(ApprovalRequest request);
}
