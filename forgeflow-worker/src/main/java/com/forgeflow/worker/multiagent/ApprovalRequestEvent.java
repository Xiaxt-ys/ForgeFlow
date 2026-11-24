package com.forgeflow.worker.multiagent;

public record ApprovalRequestEvent(String subtaskId, String tool, String reason)
        implements AgentEvent {
}
