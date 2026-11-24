package com.forgeflow.worker.multiagent;

public record ToolCallEvent(String subtaskId, String tool, boolean readOnly) implements AgentEvent {
}
