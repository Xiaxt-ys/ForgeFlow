package com.forgeflow.worker.multiagent;

public record ReasoningDelta(String subtaskId, String text) implements AgentEvent {
}
