package com.forgeflow.worker.multiagent;

public sealed interface AgentEvent permits ReasoningDelta, ToolCallEvent,
        ApprovalRequestEvent, SubtaskLifecycleEvent {
    String subtaskId();
}
